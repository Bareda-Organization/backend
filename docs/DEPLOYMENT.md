# School-Bus 배포·운영 절차서

실제 AWS 계정에서 **그대로 따라 배포**하는 절차서. `<계정ID>`·`<도메인>`·`<배포버킷>`·`<백업버킷>`·`<인스턴스ID>` 등 꺾쇠 값은 배포자가 직접 정하는 값 — 미완성이 아니라 정상이다.

**전제 조건**: AWS 계정(리소스 생성 권한), 도메인 1개(신규 구입도 가능), GitHub 저장소 쓰기 권한, Vercel 계정, 로컬에 AWS CLI v2 설치·`aws configure` 완료, 로컬에 Docker(BCrypt 해시·htpasswd 생성용).

설계 근거는 `docs/superpowers/specs/2026-08-10-mvp-배포-design.md` 참조. 이 문서는 그 설계를 실행 절차로 옮긴 것이다.

---

## 1. 개요

3-경로 구조 — 웹은 Vercel, API·인프라는 EC2 1대, 모바일은 앱 마켓으로 각각 독립 배포된다.

```
                    ┌───────────────────────────┐
   브라우저 ───────▶│ Vercel  app.<도메인>       │  Flutter Web(정적, prebuilt)
                    └─────────────┬─────────────┘
                                  │ https / wss(크로스오리진)
   모바일 앱 ───────────────────────┤
   (Play / App Store)             ▼
                    ┌───────────────────────────┐
                    │ EC2 t3.medium  api.<도메인>│
                    │ ┌───────────────────────┐ │
                    │ │ nginx(443, TLS 종단)  │ │
                    │ └───────────┬───────────┘ │
                    │             ▼             │
                    │  backend(Spring, 1개)     │
                    │             │             │
                    │  postgres · redis · kafka │
                    └──────────┬────────────────┘
                               │
              ECR(이미지) ◀── GitHub Actions ──▶ S3(배포파일·백업)
```

- **웹**: `frontend/` → GitHub Actions 가 `flutter build web` 실행 후 Vercel 에 prebuilt 배포(`.github/workflows/deploy-web.yml`)
- **API·인프라**: `backend/` + `docker-compose.prod.yml` → GitHub Actions 가 이미지 빌드해 ECR 에 올리고 SSM 으로 EC2 에 배포(`.github/workflows/deploy-backend.yml`)
- **모바일**: `frontend/` → 로컬에서 수동 빌드 후 스토어 콘솔에 직접 업로드(§9)

**⚠️ 단일 인스턴스 제약 — 백엔드 인스턴스를 늘리면 안 된다.** `docker-compose.prod.yml`(backend 서비스 주석)·`infra/proxy/nginx.prod.conf`(`upstream backend_pool` 주석)에 동일한 경고가 있다. 이유 3가지:

1. `@Scheduled` 4개(위치 시뮬레이션·연결끊김·근접/미승차·SOS 에스컬레이션)가 인스턴스마다 중복 실행돼 같은 알림이 여러 번 발송
2. 버스 위치가 `InMemoryBusLocationRepository` — 인스턴스 간 공유 불가(학생 위치는 `RedisLocationRepository` 로 이미 해결)
3. WebSocket STOMP 세션이 인스턴스에 고정 — Redis 브로커 릴레이 없이는 로드밸런싱 불가

해소하려면 위 3가지를 먼저 손봐야 한다. 상세는 설계 문서 §4.2 참조.

---

## 2. 최초 구축

### 2.1 리소스 생성 순서 요약

VPC 기본 사용 → EC2(+ EIP) → ECR → S3 버킷 2개 → IAM 인스턴스 역할 → IAM OIDC 역할 → SSM 파라미터 9개 → EC2 부트스트랩 → 도메인 연결 → 인증서 발급 → nginx 설정 치환 → **`.htpasswd` 생성(EC2)** → GitHub 시크릿·변수 등록 → 최초 배포.

**⚠️ 순서가 중요한 지점 셋**: (a) `api` A 레코드(§2.9)를 인증서 발급(§2.10)보다 **먼저** 끝내야 한다 — HTTP-01 챌린지가 도메인을 조회해 EIP 로 접속하므로 A 레코드가 없으면 최초 발급이 실패한다. (b) 그 인증서는 `docker compose up`(proxy 포함)을 **한 번도 돌리기 전에** 발급해야 한다 — 인증서가 없으면 nginx 의 443 블록이 기동 자체를 못 해 순환 의존이 생긴다. (c) `docker-compose.prod.yml` 의 proxy 서비스가 요구하는 `infra/proxy/.htpasswd` 는 `.gitignore:26` 에 등록된 비밀 파일이다 — 저장소에도 배포용 S3 버킷에도 두지 않고 **EC2 에 직접 1회 생성**한다(이유·절차는 §2.12). 최초 배포(§2.14) 전에 반드시 끝낼 것.

리전은 `ap-northeast-2`(서울)로 고정 — `deploy.sh`·`backup-db.sh`·`docker-compose.prod.yml`(`awslogs-region` 기본값)·`deploy-backend.yml`(`env.AWS_REGION`)이 모두 이 값을 기본값으로 쓴다. `deploy-web.yml` 은 AWS 를 호출하지 않아 region 개념 자체가 부재.

### 2.2 VPC·보안그룹

기본 VPC 를 그대로 쓴다(신규 VPC 불필요).

```bash
VPC_ID=$(aws ec2 describe-vpcs --filters Name=is-default,Values=true \
  --query 'Vpcs[0].VpcId' --output text)
SUBNET_ID=$(aws ec2 describe-subnets --filters Name=vpc-id,Values="$VPC_ID" \
  --query 'Subnets[0].SubnetId' --output text)

SG_ID=$(aws ec2 create-security-group --group-name school-bus-demo-sg \
  --description "School-Bus demo EC2 - 80/443 only" --vpc-id "$VPC_ID" \
  --query 'GroupId' --output text)
aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 80  --cidr 0.0.0.0/0
aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 443 --cidr 0.0.0.0/0
```

인바운드는 **80·443 뿐**(검증 기준 §2.14 의 8번 항목 참조). 22 번(SSH) 은 열지 않는다 — 접속은 SSM Session Manager 로만 한다(`bootstrap-ec2.sh` 주석 참조).

### 2.3 IAM 인스턴스 역할

EC2 가 SSM 파라미터 조회·ECR pull·S3 읽기/쓰기를 하려면 인스턴스 역할이 필요하다.

```bash
cat > ec2-trust-policy.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "ec2.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}
JSON
aws iam create-role --role-name school-bus-ec2-role \
  --assume-role-policy-document file://ec2-trust-policy.json

aws iam attach-role-policy --role-name school-bus-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam attach-role-policy --role-name school-bus-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly

cat > ec2-inline-policy.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SsmParameterRead",
      "Effect": "Allow",
      "Action": ["ssm:GetParameter", "ssm:GetParameters"],
      "Resource": "arn:aws:ssm:ap-northeast-2:<계정ID>:parameter/school-bus/demo/*"
    },
    {
      "Sid": "SsmParameterDecrypt",
      "Effect": "Allow",
      "Action": "kms:Decrypt",
      "Resource": "*"
    },
    {
      "Sid": "DeployBucketRead",
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::<배포버킷>", "arn:aws:s3:::<배포버킷>/*"]
    },
    {
      "Sid": "BackupBucketReadWrite",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::<백업버킷>", "arn:aws:s3:::<백업버킷>/*"]
    },
    {
      "Sid": "CloudWatchLogsWrite",
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup", "logs:CreateLogStream",
        "logs:PutLogEvents", "logs:DescribeLogStreams"
      ],
      "Resource": "arn:aws:logs:ap-northeast-2:<계정ID>:log-group:/school-bus/demo*"
    }
  ]
}
JSON
aws iam put-role-policy --role-name school-bus-ec2-role \
  --policy-name school-bus-ec2-inline --policy-document file://ec2-inline-policy.json

aws iam create-instance-profile --instance-profile-name school-bus-ec2-role
aws iam add-role-to-instance-profile \
  --instance-profile-name school-bus-ec2-role --role-name school-bus-ec2-role
```

`SsmParameterDecrypt` 를 `*` 로 둔 이유 — 기본 AWS 관리형 SSM 키(`alias/aws/ssm`)는 IAM 정책 리소스에 별칭으로 안전하게 좁히기 어렵다. 자체 KMS 키를 쓴다면 해당 키 ARN 으로 좁힌다.

**`CloudWatchLogsWrite` 는 생략 불가.** `docker-compose.prod.yml` 이 6개 서비스 전부에 `driver: awslogs` + `awslogs-create-group: "true"` 를 건다. Docker 는 로깅 드라이버를 **컨테이너 프로세스 시작 전에** 초기화하고 awslogs 는 그 시점에 `CreateLogGroup`·`CreateLogStream` 을 동기 호출하므로, 권한이 없으면 `failed to initialize logging driver: AccessDeniedException` 으로 **컨테이너 기동 자체가 실패**한다(postgres 부터 막혀 최초 배포가 통째로 실패). 두 관리형 정책(`AmazonSSMManagedInstanceCore`·`AmazonEC2ContainerRegistryReadOnly`) 어느 쪽도 `logs:*` 를 주지 않는다. 리소스 끝의 `*` 는 로그그룹(`:log-group:/school-bus/demo`)과 그 안의 스트림(`:log-group:/school-bus/demo:log-stream:*`)을 함께 덮기 위한 것 — 떼면 `CreateLogStream` 이 거부된다. 로그그룹명은 compose 의 `awslogs-group` 값과 일치해야 한다. CloudWatch 수집 자체를 쓰지 않겠다면 compose 의 `x-logging` 앵커와 각 서비스의 `logging: *cloudwatch` 줄을 빼는 쪽이 맞다.

### 2.4 EC2 생성

```bash
AMI_ID=$(aws ssm get-parameters \
  --names /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --query 'Parameters[0].Value' --output text)

INSTANCE_ID=$(aws ec2 run-instances \
  --image-id "$AMI_ID" --instance-type t3.medium \
  --security-group-ids "$SG_ID" --subnet-id "$SUBNET_ID" \
  --iam-instance-profile Name=school-bus-ec2-role \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3"}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=school-bus-demo}]' \
  --query 'Instances[0].InstanceId' --output text)

ALLOC_ID=$(aws ec2 allocate-address --domain vpc --query 'AllocationId' --output text)
aws ec2 associate-address --instance-id "$INSTANCE_ID" --allocation-id "$ALLOC_ID"
EIP=$(aws ec2 describe-addresses --allocation-ids "$ALLOC_ID" --query 'Addresses[0].PublicIp' --output text)
echo "인스턴스 ID: $INSTANCE_ID / 고정 IP: $EIP"
```

Elastic IP 를 붙이는 이유 — 인스턴스를 재시작(`stop`/`start`)하면 퍼블릭 IP 가 바뀐다. 도메인 A 레코드가 가리키는 대상이 계속 유효하려면 고정 IP 가 필요하다. `t3.medium`(4GB) 선정 근거는 설계 문서 §2.8(메모리 사이징 표) 참조. 디스크 30GB 는 설계 문서에 별도 산정 근거가 없는 값 — 부족 시 EBS 볼륨 확장으로 대응.

### 2.5 ECR·S3 버킷

```bash
aws ecr create-repository --repository-name school-bus-backend --region ap-northeast-2
```

리포지토리 이름은 **`school-bus-backend` 고정** — `.github/workflows/deploy-backend.yml` 의 `ECR_REPOSITORY` 값과 일치해야 한다.

```bash
for BUCKET in <배포버킷> <백업버킷>; do
  aws s3api create-bucket --bucket "$BUCKET" --region ap-northeast-2 \
    --create-bucket-configuration LocationConstraint=ap-northeast-2
  aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
done

cat > backup-lifecycle.json <<'JSON'
{
  "Rules": [{
    "ID": "expire-db-backups-7d",
    "Filter": {"Prefix": "db/"},
    "Status": "Enabled",
    "Expiration": {"Days": 7}
  }]
}
JSON
aws s3api put-bucket-lifecycle-configuration --bucket <백업버킷> \
  --lifecycle-configuration file://backup-lifecycle.json
```

배포용 버킷은 `infra/`·`docker-compose.prod.yml` 을 담는 통로(`.github/workflows/deploy-backend.yml` 의 "배포 파일 S3 동기화" 스텝), 백업용 버킷은 `infra/scripts/backup-db.sh` 가 매일 올리는 `pg_dump` 결과 저장소다. 백업 버킷의 `db/` 접두사에만 7일 수명주기를 건다.

### 2.6 IAM OIDC 역할 (GitHub Actions 용)

GitHub Actions 는 장기 액세스키 없이 OIDC 로 이 역할을 assume 한다(`deploy-backend.yml` `permissions: id-token: write`, `deploy-web.yml` 은 Vercel 토큰만 쓰므로 이 역할이 불필요).

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

지문(thumbprint)은 AWS 콘솔(IAM → ID 공급자 → 공급자 추가 → OpenID Connect → URL 입력 후 "지문 가져오기")이 자동 조회한 값을 우선 신뢰한다 — CLI 에 직접 박아 넣은 값은 시간이 지나면 stale 해질 수 있다.

```bash
cat > gha-trust-policy.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Federated": "arn:aws:iam::<계정ID>:oidc-provider/token.actions.githubusercontent.com"},
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {"token.actions.githubusercontent.com:aud": "sts.amazonaws.com"},
      "StringLike": {"token.actions.githubusercontent.com:sub": "repo:mskim98/School-Bus:ref:refs/heads/main"}
    }
  }]
}
JSON
aws iam create-role --role-name school-bus-gha-role \
  --assume-role-policy-document file://gha-trust-policy.json
```

`sub` 조건을 `refs/heads/main` 으로 좁혔다 — `workflow_dispatch` 를 다른 브랜치에서 수동 실행하면 이 조건에 안 걸려 역할 assume 이 실패한다. main 외 브랜치에서도 수동 배포가 필요하면 조건을 넓힌다.

```bash
cat > gha-permissions.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {"Sid": "EcrAuth", "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*"},
    {
      "Sid": "EcrPush", "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage",
        "ecr:PutImage", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload"
      ],
      "Resource": "arn:aws:ecr:ap-northeast-2:<계정ID>:repository/school-bus-backend"
    },
    {
      "Sid": "DeployBucketWrite", "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:DeleteObject", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::<배포버킷>", "arn:aws:s3:::<배포버킷>/*"]
    },
    {
      "Sid": "SsmDeploy", "Effect": "Allow", "Action": "ssm:SendCommand",
      "Resource": [
        "arn:aws:ec2:ap-northeast-2:<계정ID>:instance/<인스턴스ID>",
        "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript"
      ]
    },
    {
      "Sid": "SsmCommandStatus", "Effect": "Allow",
      "Action": ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"],
      "Resource": "*"
    }
  ]
}
JSON
aws iam put-role-policy --role-name school-bus-gha-role \
  --policy-name school-bus-gha-inline --policy-document file://gha-permissions.json
```

역할 ARN(`arn:aws:iam::<계정ID>:role/school-bus-gha-role`)을 §2.13 의 `AWS_DEPLOY_ROLE_ARN` 시크릿에 등록한다.

### 2.7 SSM 파라미터 등록

§3 의 표를 그대로 따라 9개를 등록한다. 시드 비밀번호 해시 생성은 §4 참조.

### 2.8 EC2 부트스트랩

`bootstrap-ec2.sh`·`docker-compose.prod.yml`·`infra/` 는 저장소 안 파일이지만, EC2 는 저장소를 클론하지 않는다(비공개 저장소 자격증명을 서버에 두지 않기 위해 — `deploy.sh` 상단 주석). 그래서 **최초 1회는 로컬에서 S3 로 수동 업로드**해 부트스트랩 재료를 만든다(이후 배포부터는 GitHub Actions 가 같은 동작을 자동으로 한다).

```bash
# 1) 로컬 저장소 루트에서 — CI 가 매 배포마다 하는 것과 동일한 동작을 최초 1회 수동으로 수행
#    --exclude 는 CI(deploy-backend.yml)와 동일하게 붙인다. 작업 트리에 실수로 만들어 둔
#    .htpasswd 가 배포용 S3 버킷에 시크릿 그대로 올라가는 걸 막는다.
aws s3 sync infra "s3://<배포버킷>/infra" --exclude "proxy/.htpasswd"
aws s3 cp docker-compose.prod.yml "s3://<배포버킷>/docker-compose.prod.yml"

# 2) SSM Session Manager 로 EC2 접속 (SSH 불필요)
aws ssm start-session --target "$INSTANCE_ID"
```

접속한 세션 안에서:

```bash
sudo mkdir -p /opt/school-bus
sudo aws s3 sync "s3://<배포버킷>/infra" /opt/school-bus/infra
sudo aws s3 cp "s3://<배포버킷>/docker-compose.prod.yml" /opt/school-bus/docker-compose.prod.yml
sudo chmod +x /opt/school-bus/infra/scripts/*.sh /opt/school-bus/infra/certbot/*.sh

cd /opt/school-bus/infra/scripts
sudo BACKUP_BUCKET=<백업버킷> bash bootstrap-ec2.sh
```

`bootstrap-ec2.sh` 가 하는 일(주석 근거): docker·`cronie` 설치, compose v2 플러그인 설치, `/opt/school-bus` 디렉터리 생성, 스왑 2GB(배포 순간 신·구 컨테이너 동시 실행 시 OOM 방지), DB 백업 크론(매일 03:10, `/etc/cron.d/schoolbus-backup`) 등록, `crond` 활성 확인.

`cronie` 를 명시적으로 설치하는 이유 — Amazon Linux 2023 은 cron 을 기본 포함하지 않는다(AWS 는 systemd timer 대체를 권고). 없는 채로 두면 백업이 조용히 영원히 미실행. 스크립트 마지막의 `systemctl is-active crond` 검사가 그 상태로 완료 처리되는 것을 막는다.

### 2.9 도메인 연결

- `api.<도메인>` → A 레코드 → §2.4 의 Elastic IP
- `app.<도메인>` → Vercel 프로젝트 생성 후 Vercel "Domains" 설정에서 안내하는 CNAME/A 레코드로 연결(Vercel 콘솔 절차를 따른다)

**`api` A 레코드는 §2.10 인증서 발급보다 먼저 끝내야 한다.** Let's Encrypt 의 HTTP-01 챌린지는 Let's Encrypt 서버가 `api.<도메인>` 을 직접 조회해 EIP 의 80 포트로 접속하는 방식이라, A 레코드가 없거나 아직 전파되지 않았으면 발급이 실패한다. `app` 쪽 레코드는 인증서와 무관하므로 나중에 해도 된다.

전파 확인(로컬에서):

```bash
dig +short api.<도메인>    # §2.4 의 Elastic IP 가 나와야 한다
```

### 2.10 인증서 최초 발급

**전제: §2.9 의 `api` A 레코드가 EIP 를 가리키고 있어야 한다**(HTTP-01 챌린지가 도메인으로 되돌아온다).

**docker compose 를 한 번도 올리기 전에 실행한다** — proxy 컨테이너가 아직 없어 80 포트가 비어 있어야 `certonly --standalone` 이 성공한다. SSM Session Manager 로 EC2 에 접속해(§2.8 과 같은 방법):

```bash
cd /opt/school-bus/infra/certbot
sudo ./init-cert.sh api.<도메인> <운영담당 이메일>
```

발급된 인증서는 `school-bus_certbot-conf` 도커 볼륨에 저장된다(`init-cert.sh` 의 `CONF_VOLUME` — compose 프로젝트명이 디렉터리명 `school-bus` 에서 나오므로 `/opt/school-bus` 외 경로에 배치했다면 `CONF_VOLUME` 환경변수로 맞춘다).

### 2.11 nginx 설정 치환

`infra/proxy/nginx.prod.conf` 에 `api.example.com`(4곳 — `server_name` 2곳, `ssl_certificate`·`ssl_certificate_key` 각 1곳)·`app.example.com`(1곳)이 자리표시자로 박혀 있다. nginx 는 환경변수를 못 읽으므로 파일 자체를 고쳐 커밋한다.

```bash
sed -i '' 's/api\.example\.com/api.<도메인>/g; s/app\.example\.com/app.<도메인>/g' \
  infra/proxy/nginx.prod.conf
git add infra/proxy/nginx.prod.conf
git commit -m "chore(deploy): nginx 도메인 치환"
```

(macOS `sed -i ''` 기준 — Linux 는 `sed -i` 로 따옴표 없이 실행한다.) 이 커밋을 `main` 에 push 하면 §2.14 최초 배포가 자동으로 트리거된다. `.htpasswd`(§2.12)와 GitHub 시크릿(§2.13)을 아직 안 넣었다면 워크플로가 실패하니 그 둘을 먼저 끝낸다.

### 2.12 Swagger Basic Auth 계정 (`.htpasswd`)

`docker-compose.prod.yml` 의 proxy 서비스가 `infra/proxy/.htpasswd` 를 필수로 마운트한다. 이 파일은 `.gitignore:26` 에 등록돼 있어 git 커밋 대상이 아니다.

**저장소·S3 를 거치지 않고 EC2 에 직접 생성한다.** `.github/workflows/deploy-backend.yml` 의 두 `s3 sync`(러너→S3, S3→EC2)는 각각 `--exclude "proxy/.htpasswd"` 를 붙여 이 파일을 동기화 대상에서 제외한다. `aws s3 sync` 의 `--exclude` 는 전송뿐 아니라 `--delete` 삭제 후보 판정에도 같은 패턴을 적용하므로(`aws s3 sync help`), 설령 S3 에 이 파일을 올려도 EC2 로는 내려받아지지 않는다 — 즉 이 파일을 다루는 유일하게 유효한 경로는 EC2 로컬 디스크뿐이다.

SSM Session Manager 로 EC2 에 접속해(§2.4 의 인스턴스 ID 사용) 최초 1회 생성한다:

```bash
aws ssm start-session --target "$INSTANCE_ID"
```

접속한 세션 안에서:

```bash
sudo mkdir -p /opt/school-bus/infra/proxy
sudo docker run --rm httpd:alpine htpasswd -nbB <Swagger계정> '<Swagger비밀번호>' \
  | sudo tee /opt/school-bus/infra/proxy/.htpasswd > /dev/null
```

최초 배포(§2.14) 전에 끝내야 한다 — 없으면 proxy 컨테이너가 `auth_basic_user_file` 대상을 열지 못해 기동에 실패한다.

**즉시 복구 — Swagger UI 가 401 을 내거나 proxy 컨테이너가 재시작을 반복할 때**(EC2 디스크 손상·수동 삭제 등 배포 경로 밖의 원인으로 파일이 사라진 경우):

```bash
# 1) SSM Session Manager 로 EC2 접속
aws ssm start-session --target "$INSTANCE_ID"

# 2) 파일 재생성 (EC2 위에서)
sudo mkdir -p /opt/school-bus/infra/proxy
sudo docker run --rm httpd:alpine htpasswd -nbB <Swagger계정> '<Swagger비밀번호>' \
  | sudo tee /opt/school-bus/infra/proxy/.htpasswd > /dev/null

# 3) proxy 컨테이너 재기동 (바인드 마운트라 컨테이너를 다시 띄워야 반영)
sudo docker compose -f /opt/school-bus/docker-compose.prod.yml \
  --env-file /opt/school-bus/.env restart proxy
```

배포가 이 파일을 지우던 결함은 위 `--exclude` 적용으로 이미 해소돼 배포 후 재업로드가 불필요하다(§5) — 그래도 파일이 없는 상태를 만났다면 위 절차로 즉시 복구한다.

### 2.13 GitHub 시크릿·변수 등록

저장소 Settings → Secrets and variables → Actions 에서 등록. 이름은 워크플로 파일이 실제로 참조하는 것과 정확히 일치해야 한다.

| 워크플로 | 종류 | 이름 | 값 |
|---|---|---|---|
| `deploy-backend.yml` | Secret | `AWS_DEPLOY_ROLE_ARN` | §2.6 에서 만든 역할 ARN |
| `deploy-backend.yml` | Secret | `DEPLOY_BUCKET` | §2.5 배포용 S3 버킷명 |
| `deploy-backend.yml` | Secret | `EC2_INSTANCE_ID` | §2.4 인스턴스 ID |
| `deploy-web.yml` | Variable | `API_BASE_URL` | `https://api.<도메인>` |
| `deploy-web.yml` | Secret | `VERCEL_ORG_ID` | Vercel 조직/계정 ID |
| `deploy-web.yml` | Secret | `VERCEL_PROJECT_ID` | Vercel 프로젝트 ID |
| `deploy-web.yml` | Secret | `VERCEL_TOKEN` | Vercel 계정 설정에서 발급한 토큰 |

`API_BASE_URL` 은 **Variable**(Secret 아님) — 워크플로가 `vars.API_BASE_URL` 로 읽는다.

### 2.14 최초 배포·검증

§2.11 커밋을 push 하면(또는 Actions 탭에서 `deploy-backend.yml`·`deploy-web.yml` 을 각각 `workflow_dispatch` 로) 최초 배포가 실행된다. 검증 기준(설계 문서 §8, 9개 전부 통과해야 완료로 간주):

1. 컨테이너 내부 `/actuator/health` 가 `UP`(DataSource·Redis 포함, Kafka 는 기본 헬스 인디케이터에 미포함). 외부에서는 404
2. `https://app.<도메인>` 에서 데모 계정 로그인 성공
3. 관리자 관제 화면에서 버스 마커가 실제로 이동
4. 배차 시뮬레이션 실행이 500 미발생
5. WebSocket 이 `wss` 로 연결되고 위치 갱신 수신
6. `main` push 시 자동 재배포 후 1~5 재통과
7. 브라우저 콘솔에 CORS 오류 부재
8. `nmap <EIP>` 기준 개방 포트가 80·443 뿐(22·5432·6379·9092 폐쇄)
9. `pg_dump` 백업이 S3 에 적재, 복구 리허설 1회 성공(§7)

**최초 배포는 10분 이상 걸릴 수 있다.** 이미지 6종을 처음 받고(백엔드 ~400MB + 인프라 ~700MB) kafka·backend 의 `start_period`(40s·90s)와 Flyway 마이그레이션이 순차로 붙는다. 워크플로의 상태 판정 상한은 이를 감안한 20분(`MAX_WAIT_SECONDS=1200`) — 진행 중인데 실패로 오판해 운영자가 재실행하면 중복 배포가 큐에 쌓인다(`concurrency: cancel-in-progress: false`).

---

## 3. SSM 파라미터 목록

파라미터 경로 접두사는 `/school-bus/demo/` — `infra/scripts/deploy.sh` 의 `PARAM_PREFIX="/school-bus/demo"` 와 일치해야 한다. 아래 9개는 `deploy.sh` 가 `get_param` 으로 실제 조회하는 이름 전부다(하나라도 빠지거나 값이 비어 있으면 `get_param` 이 에러를 stderr 에 남기고 배포가 그 자리에서 실패 — 조용히 넘어가지 않는다).

| 이름 | 타입 | 값/생성법 |
|---|---|---|
| `/school-bus/demo/ECR_REGISTRY` | String | `<계정ID>.dkr.ecr.ap-northeast-2.amazonaws.com` |
| `/school-bus/demo/DB_PASSWORD` | SecureString | `openssl rand -base64 24` |
| `/school-bus/demo/JWT_SECRET` | SecureString | `openssl rand -base64 48`(32바이트 이상 필수 — `jwt.secret` 이 서명 키로 직접 쓰임) |
| `/school-bus/demo/SEED_PASSWORD_HASH` | SecureString | §4 참조. `demo` 프로파일이 Flyway placeholder `seedPasswordHash` 로 주입 — 미주입 시 기동 실패가 정상(placeholder 미해결) |
| `/school-bus/demo/CORS_ALLOWED_ORIGINS` | String | `https://app.<도메인>`(REST 전용. `SecurityConfig.java:39` 의 `app.cors.allowed-origins` 로 주입, `/api/**` 에만 적용) |
| `/school-bus/demo/WS_ALLOWED_ORIGIN_PATTERNS` | String | `https://app.<도메인>`(STOMP 전용. **`CORS_ALLOWED_ORIGINS` 와 별개** — WebSocket 핸드셰이크는 CORS 필터를 타지 않고 `WebSocketConfig` 의 `setAllowedOriginPatterns` 로 별도 검증) |
| `/school-bus/demo/NAVER_DIRECTIONS_KEY_ID` | SecureString | NCP 콘솔에서 Directions API 발급. **키 미보유 시에도 반드시 등록** — 아래 참고 |
| `/school-bus/demo/NAVER_DIRECTIONS_KEY` | SecureString | NCP 콘솔에서 Directions API 발급. **키 미보유 시에도 반드시 등록** — 아래 참고 |
| `/school-bus/demo/ROUTING_PROVIDER` | String | `naver`(NCP 키 없으면 `osrm` 로 무료 대체 — `application.yml` 의 `routing.provider`) |

**⚠️ NCP 키가 없어도 위 두 항목은 등록해야 한다.** `deploy.sh` 의 `get_param` 은 9개 전부를 필수로 보고, 파라미터가 없거나 값이 비면 **1단계에서 배포를 중단**한다(`ROUTING_PROVIDER=osrm` 만 등록하고 두 키를 비워두면 배포 자체가 진행되지 않는다). `osrm` 폴백을 쓰려면 두 항목에 `unused` 같은 임의 문자열을 넣어 등록하고 `ROUTING_PROVIDER` 를 `osrm` 으로 둔다 — `osrm` 일 때 앱은 이 두 값을 읽지 않는다. "필수 9개" 라는 단순한 계약을 유지하려는 의도적 설계(선택 항목을 섞으면 어떤 값이 비어도 되는지가 스크립트·문서·compose 세 곳에서 갈린다).

```bash
# String 예시
aws ssm put-parameter --name /school-bus/demo/ECR_REGISTRY --type String \
  --value "<계정ID>.dkr.ecr.ap-northeast-2.amazonaws.com"

# SecureString 예시
aws ssm put-parameter --name /school-bus/demo/DB_PASSWORD --type SecureString \
  --value "$(openssl rand -base64 24)"
```

**배포자 참고**: `SEED_PASSWORD_HASH`·`WS_ALLOWED_ORIGIN_PATTERNS` 를 읽어 쓰는 `demo` 프로파일·Flyway placeholder 배선은 `backend/src/main/resources/application.yml` 에 반영 완료. 두 값 모두 기본값 없이 요구하므로 미등록 시 컨테이너가 기동 단계에서 실패한다(조용히 약한 값으로 뜨지 않는다). 이 성질은 `DeploymentConfigGuardTest` 가 회귀를 막는다.

---

## 4. 시드 비밀번호 생성

데모 계정 비밀번호를 정하고 BCrypt 해시로 변환해 SSM 에 등록하는 절차.

```bash
# 원하는 비밀번호의 BCrypt 해시 생성 (htpasswd 의 -B 가 bcrypt)
docker run --rm httpd:alpine htpasswd -nbB demo '원하는비밀번호' | cut -d: -f2
# 결과($2y$...)를 SEED_PASSWORD_HASH 로 등록한다.
aws ssm put-parameter --name /school-bus/demo/SEED_PASSWORD_HASH --type SecureString \
  --value '$2y$...앞 명령 결과...'
```

⚠️ Spring 의 `BCryptPasswordEncoder` 는 `$2a`·`$2y` 를 모두 검증한다 — `htpasswd` 산출물이 `$2y$` 접두인 것을 그대로 써도 무방하다.

---

## 5. 일상 배포

`main` push 중 `backend/**`·`docker-compose.prod.yml`·`infra/**` 변경분이 있으면 `deploy-backend.yml` 이 자동 실행(테스트 → 이미지 빌드 → ECR → SSM Send Command → EC2 배포). `frontend/**` 변경은 `deploy-web.yml` 이 별도로 자동 실행.

수동 트리거: GitHub 저장소 Actions 탭 → 해당 워크플로 선택 → `Run workflow`(`workflow_dispatch`).

동시 배포 처리: 백엔드는 `concurrency: cancel-in-progress: false` — 겹치면 취소 대신 줄을 세운다(옛 이미지가 새 이미지를 덮어쓰는 사고 방지). 웹은 `cancel-in-progress: true` — 마지막 push 만 반영된다.

`.htpasswd` 는 `infra/**` 배포에 영향받지 않는다 — `deploy-backend.yml` 의 두 `s3 sync` 가 `--exclude "proxy/.htpasswd"` 로 이 파일을 동기화·삭제 대상에서 제외한다(§2.12). 배포마다 재업로드는 불필요.

---

## 6. 롤백

이전 커밋 SHA 로 되돌리는 법. Actions 재실행보다 EC2 에서 직접 돌리는 편이 빠르다(빌드를 다시 하지 않고 이미 ECR 에 있는 과거 태그를 그대로 재사용).

```bash
# EC2 에서 (SSM Session Manager 접속 후)
sudo /opt/school-bus/infra/scripts/deploy.sh <이전_커밋_SHA>
```

**`<이전_커밋_SHA>` 는 40자 full SHA 다.** 워크플로가 `${{ github.sha }}`(40자)로 태그를 붙여 push 하므로, GitHub 화면에서 흔히 보이는 short SHA(7자)로 부르면 ECR 에 그런 태그가 없어 `pull` 단계에서 막힌다. `git rev-parse <short>` 로 펼쳐 쓴다.

`<이전_커밋_SHA>` 는 ECR 에 그 태그의 이미지가 아직 남아 있어야 동작한다(이미지 보관 주기를 별도로 관리하지 않으므로 오래된 태그는 수동 정리 전까지 계속 남는다). `deploy.sh` 는 **백엔드 이미지 태그만 되돌린다** — `infra/`·`docker-compose.prod.yml` 자체는 EC2 에 이미 동기화된 최신 상태 그대로 유지된다. 즉 인프라 설정까지 과거로 되돌리려면 별도로 그 시점의 파일을 S3/EC2 에 다시 올려야 한다.

---

## 7. DB 복구 리허설

**반드시 1회 수행 후 결과를 이 문서 하단(§7.1)에 기록한다.** 이번 절차서 작성 시점에는 실 AWS 계정·백업 데이터가 없어 **미수행** — 최초 배포 완료 후 담당자가 직접 1회 실행하고 결과를 남긴다.

```bash
sudo aws s3 cp s3://<백업버킷>/db/<날짜시각>.sql.gz - | gunzip \
  | sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env \
    exec -T postgres psql -U schoolbus schoolbus
```

`<날짜시각>` 형식은 `backup-db.sh` 의 `STAMP="$(date +%F-%H%M)"` 그대로다(예: `2026-08-11-0310`) — 정확한 파일명은 `aws s3 ls s3://<백업버킷>/db/` 로 목록을 먼저 확인한다.

**⚠️ 위 명령을 그대로 실행하면 대상이 이미 데이터가 들어 있는 운영 DB(`schoolbus`) 라는 점에 주의한다.** `backup-db.sh` 의 `pg_dump` 는 `--clean` 옵션 없이 순수 `CREATE`/`INSERT` 구문만 담으므로, 이미 같은 스키마·데이터가 있는 대상에 그대로 흘려보내면 "이미 존재함" 류 오류가 대량으로 찍힌다(파괴적이지는 않으나 리허설로서 신뢰하기 어렵다). **복구 절차 자체를 검증**하려면 스크래치 DB 를 만들어 그쪽에 복원하는 편이 안전하다:

```bash
sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env \
  exec -T postgres createdb -U schoolbus schoolbus_restore_test

sudo aws s3 cp s3://<백업버킷>/db/<날짜시각>.sql.gz - | gunzip \
  | sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env \
    exec -T postgres psql -U schoolbus schoolbus_restore_test

# 확인 후 정리
sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env \
  exec -T postgres dropdb -U schoolbus schoolbus_restore_test
```

### 7.1 리허설 결과 기록란

| 수행일 | 수행자 | 대상 백업 파일 | 결과 | 비고 |
|---|---|---|---|---|
| (미기록 — 최초 배포 후 1회 수행 필요) | | | | |

---

## 8. 장애 대응

증상별 확인 순서.

| 증상 | 확인 |
|---|---|
| 로그인 401 만 반복 | `SEED_PASSWORD_HASH` 가 실제 비밀번호와 맞는지 |
| 브라우저 CORS 오류 | `CORS_ALLOWED_ORIGINS` 에 스킴 포함 정확한 출처가 있는지 |
| WebSocket 만 연결 실패 | `WS_ALLOWED_ORIGIN_PATTERNS`, nginx `/ws/` 블록의 Upgrade 헤더 |
| 배차·시뮬레이션 500 | NCP 키, 또는 `ROUTING_PROVIDER=osrm` 폴백. **폴백을 쓸 때도 `NAVER_DIRECTIONS_KEY_ID`·`NAVER_DIRECTIONS_KEY` 는 임의 값으로 등록돼 있어야 한다**(§3) — 비어 있으면 다음 배포가 `deploy.sh` 1단계에서 중단 |
| 컨테이너가 자꾸 죽음 | `free -h` 로 메모리, `docker stats`, 스왑 활성 여부 |
| 인증서 만료 | `docker compose logs certbot`, 80 포트 개방 여부 |
| Swagger UI 401/기동 실패 | §2.12 의 즉시 복구 절차(EC2 에 `.htpasswd` 재생성 → `proxy` 컨테이너 재기동) |

---

## 9. 앱 스토어 릴리스

빌드 명령.

```bash
cd frontend
flutter build appbundle --release --dart-define=API_BASE_URL=https://api.<도메인> --dart-define=ENABLE_QUICK_LOGIN=false
flutter build ipa --release --dart-define=API_BASE_URL=https://api.<도메인> --dart-define=ENABLE_QUICK_LOGIN=false
```

`ENABLE_QUICK_LOGIN=false` 는 필수다(`frontend/lib/core/config/feature_flags.dart`) — 켠 채로 배포하면 데모용 시드 계정 목록이 로그인 화면에 노출된다.

빌드에 앞서 Android 키스토어·iOS 배포 인증서/프로비저닝 프로파일이 각각 `frontend/android`·`frontend/ios` 프로젝트에 구성돼 있어야 한다(미구성 상태로는 위 두 명령 자체가 서명 단계에서 실패한다).

준비물:

- 개인정보처리방침 URL(`app.<도메인>/privacy`)
- Google Play 데이터 안전 양식(위치 데이터 수집 신고 필수)
- 백그라운드 위치 사용 시 Google 별도 심사 양식(반려 빈발 구간)
- iOS `Info.plist` 위치 권한 사용 목적 문구
- 아이콘·스크린샷

---

## 10. 알려진 한계 (수용)

설계 문서 §9 그대로.

| 한계 | 사유 |
|---|---|
| 배포 시 수십 초 다운타임 | 단일 인스턴스. §4.2 제약 해소가 선행 조건 |
| 인스턴스 사망 시 복구 수동 | 데모 성격. Auto Scaling Group 미구성 |
| DB 백업 주기 24시간(최대 24시간 유실) | RDS PITR 미채택의 대가 |
| Kafka 단일 브로커(복제 없음) | 브로커 사망 시 미소비 이벤트 유실 |
| 알람·APM 부재 | CloudWatch Logs 만 수집. 장애 인지는 수동 |
