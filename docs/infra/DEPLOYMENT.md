# School-Bus 배포·운영 절차서

실제 AWS 계정에서 **그대로 따라 배포**하는 절차서. `<계정ID>`·`<도메인>`·`<배포버킷>`·`<백업버킷>`·`<인스턴스ID>` 등 꺾쇠 값은 배포자가 직접 정하는 값 — 미완성이 아니라 정상이다.

> ⚠ **2026-09-09 — 이 저장소는 백엔드 전용이다.** `frontend/` 와 `.github/workflows/deploy-web.yml` 을 삭제했으므로 **웹(Vercel)·모바일(스토어) 경로는 이 저장소에서 실행 불가**. 아래 §1 의 3-경로 구조와 §9 모바일 빌드는 **프론트를 되살릴 때 쓰는 보존 절차**로 읽는다(이전 `main` 트리에 245파일 보존). 지금 실행 가능한 것은 **API·인프라 경로(EC2 + `deploy-backend.yml`)** 뿐이다.

**전제 조건**: AWS 계정(리소스 생성 권한), 도메인 1개(신규 구입도 가능), GitHub 저장소 쓰기 권한, ~~Vercel 계정~~(웹 경로 부재), 로컬에 AWS CLI v2 설치·`aws configure` 완료, 로컬에 Docker(BCrypt 해시·htpasswd 생성용).

설계 근거는 `docs/archive/specs/2026-08-10-mvp-배포-design.md` 참조. 이 문서는 그 설계를 실행 절차로 옮긴 것이다.

---

## 1. 개요

3-경로 구조 — 웹은 Vercel, API·인프라는 EC2 1대, 모바일은 앱 마켓으로 각각 독립 배포된다.

```
                    ┌───────────────────────────┐
   브라우저 ───────▶│ Vercel  app.<도메인>       │  Next.js(관계자 웹). ⚠ Flutter Web 은 미사용 확정(Ruling 270)
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
                    │  postgres · redis         │
                    └──────────┬────────────────┘
                               │
              ECR(이미지) ◀── GitHub Actions ──▶ S3(배포파일·백업)
```

- ~~**웹**: `frontend/` → GitHub Actions 가 `flutter build web` 실행 후 Vercel 에 prebuilt 배포(`.github/workflows/deploy-web.yml`)~~ — **2026-09-09 삭제, 실행 불가**
- **API·인프라**: `backend/` + `docker-compose.prod.yml` → GitHub Actions 가 이미지 빌드해 ECR 에 올리고 SSM 으로 EC2 에 배포(`.github/workflows/deploy-backend.yml`) — **현재 유일하게 실행 가능한 경로**
- ~~**모바일**: `frontend/` → 로컬에서 수동 빌드 후 스토어 콘솔에 직접 업로드(§9)~~ — **2026-09-09 삭제, 실행 불가**

**⚠️ 단일 인스턴스 제약 — 백엔드 인스턴스를 늘리면 안 된다.** `docker-compose.prod.yml`(backend 서비스 주석)·`infra/proxy/nginx.prod.conf`(`upstream backend_pool` 주석)에 동일한 경고가 있다. 이유 3가지:

1. `@Scheduled` 4개(위치 시뮬레이션·연결끊김·근접/미승차·SOS 에스컬레이션)가 인스턴스마다 중복 실행돼 같은 알림이 여러 번 발송
2. ~~버스 위치가 `InMemoryBusLocationRepository` — 인스턴스 간 공유 불가~~ → **해소**(2026-08-21, `RedisBusLocationRepository`). **단 이것만으로 증설 조건은 성립하지 않는다** — 1·3이 그대로 남아 금지는 유지한다
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
        "logs:PutLogEvents", "logs:DescribeLogStreams",
        "logs:PutRetentionPolicy"
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

**`CloudWatchLogsWrite` 는 생략 불가.** `docker-compose.prod.yml` 이 9개 서비스 전부에 `driver: awslogs` + `awslogs-create-group: "true"` 를 건다. Docker 는 로깅 드라이버를 **컨테이너 프로세스 시작 전에** 초기화하고 awslogs 는 그 시점에 `CreateLogGroup`·`CreateLogStream` 을 동기 호출하므로, 권한이 없으면 `failed to initialize logging driver: AccessDeniedException` 으로 **컨테이너 기동 자체가 실패**한다(postgres 부터 막혀 최초 배포가 통째로 실패). 두 관리형 정책(`AmazonSSMManagedInstanceCore`·`AmazonEC2ContainerRegistryReadOnly`) 어느 쪽도 `logs:*` 를 주지 않는다. 리소스 끝의 `*` 는 로그그룹(`:log-group:/school-bus/demo`)과 그 안의 스트림(`:log-group:/school-bus/demo:log-stream:*`)을 함께 덮기 위한 것 — 떼면 `CreateLogStream` 이 거부된다. 로그그룹명은 compose 의 `awslogs-group` 값과 일치해야 한다. CloudWatch 수집 자체를 쓰지 않겠다면 compose 의 `x-logging` 앵커와 각 서비스의 `logging: *cloudwatch` 줄을 빼는 쪽이 맞다.

**로그 보관 기간 7일(설계 문서 `docs/archive/specs/2026-08-10-mvp-배포-design.md` §2.7 · §4.8).** 로그 그룹은 Docker 가 만들어(`awslogs-create-group`) 기본이 **무기한 보관**이다 — 비용이 계속 늘고 로그에 섞인 개인정보(요청 경로 · 예외 메시지)도 무기한 남는다. `logs:PutRetentionPolicy` 를 위 정책에 넣은 이유이고, `deploy.sh` 가 배포 성공 뒤마다 아래 명령을 멱등하게 실행한다(실패해도 배포는 성공 — 경고만). 첫 배포 전에 손으로 걸어 두려면 그룹을 먼저 만든다.

```bash
aws logs create-log-group --log-group-name /school-bus/demo --region ap-northeast-2   # 이미 있으면 오류 — 무시
aws logs put-retention-policy --log-group-name /school-bus/demo --retention-in-days 7 --region ap-northeast-2
aws logs describe-log-groups --log-group-name-prefix /school-bus/demo --query 'logGroups[].retentionInDays'   # → [7]
```

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

**ECR 수명주기 — 최근 10개만 남긴다.** 매 배포가 새 40자 SHA 태그라 두면 이미지가 무기한 쌓인다(저장 비용 · 롤백 후보가 무엇인지 알 수 없음). 롤백(§6)은 이 10개 안에서만 된다.

```bash
cat > ecr-lifecycle.json <<'JSON'
{
  "rules": [{
    "rulePriority": 1,
    "description": "최근 이미지 10개만 보관",
    "selection": {"tagStatus": "any", "countType": "imageCountMoreThan", "countNumber": 10},
    "action": {"type": "expire"}
  }]
}
JSON
aws ecr put-lifecycle-policy --repository-name school-bus-backend --region ap-northeast-2 \
  --lifecycle-policy-text file://ecr-lifecycle.json
```

```bash
for BUCKET in <배포버킷> <백업버킷>; do
  aws s3api create-bucket --bucket "$BUCKET" --region ap-northeast-2 \
    --create-bucket-configuration LocationConstraint=ap-northeast-2
  aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
done

cat > backup-lifecycle.json <<'JSON'
{
  "Rules": [
    {
      "ID": "expire-db-backups-7d",
      "Filter": {"Prefix": "db/"},
      "Status": "Enabled",
      "Expiration": {"Days": 7}
    },
    {
      "ID": "expire-photo-backups-7d",
      "Filter": {"Prefix": "photos/"},
      "Status": "Enabled",
      "Expiration": {"Days": 7}
    }
  ]
}
JSON
aws s3api put-bucket-lifecycle-configuration --bucket <백업버킷> \
  --lifecycle-configuration file://backup-lifecycle.json
```

배포용 버킷은 `infra/`·`docker-compose.prod.yml` 을 담는 통로(`.github/workflows/deploy-backend.yml` 의 "배포 파일 S3 동기화" 스텝), 백업용 버킷은 `infra/scripts/backup-db.sh` 가 매일 올리는 `pg_dump` 결과(`db/`)와 학생 사진 묶음(`photos/`) 저장소다. 두 접두사에 각각 7일 수명주기를 건다.

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

§3 의 표를 그대로 따라 **필수 11개**(+ `prod` 면 FCM 3개)를 등록한다. 선택 항목은 필요할 때 등록한다. 시드 비밀번호 해시 생성은 §4 참조.

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

`bootstrap-ec2.sh` 가 하는 일(주석 근거): docker·`cronie` 설치, compose v2 플러그인 설치, `/opt/school-bus` 디렉터리 생성, 스왑 2GB(배포 순간 신·구 컨테이너 동시 실행 시 OOM 방지), DB·학생 사진 백업 크론(매일 03:10, `/etc/cron.d/schoolbus-backup`) 등록, `crond` 활성 확인.

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

(macOS `sed -i ''` 기준 — Linux 는 `sed -i` 로 따옴표 없이 실행한다.) `deploy-backend.yml` 은 수동 실행 전용(2026-09-09)이라 이 커밋을 push 해도 배포되지 않음 — §2.14 에서 직접 실행. `.htpasswd`(§2.12)와 GitHub 시크릿(§2.13)을 아직 안 넣었다면 워크플로가 실패하니 그 둘을 먼저 끝낸다.

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

§2.11 커밋을 push 한 뒤 Actions 탭에서 `deploy-backend.yml` 을 `workflow_dispatch` 로 실행하면 최초 배포가 실행된다(push 자동 배포는 없음 — 웹 배포 워크플로 `deploy-web.yml` 은 2026-09-09 제거). 검증 기준(설계 문서 §8, 9개 전부 통과해야 완료로 간주):

1. 컨테이너 내부 `/actuator/health` 가 `UP`(DataSource·Redis 포함). 외부에서는 `/actuator` 가 404 이고 `https://api.<도메인>/healthz` 만 200(`{"status":"UP"}` — 외부 가동 감시가 칠 주소, §11)
2. `https://app.<도메인>` 에서 로그인 성공 — `demo` 는 데모 계정, `prod` 는 첫 메인 관리자(§3 "첫 메인 관리자")
3. 관리자 관제 화면에서 버스 마커가 실제로 이동
4. 배차 시뮬레이션 실행이 500 미발생
5. WebSocket 이 `wss` 로 연결되고 위치 갱신 수신
6. 재배포(`workflow_dispatch`) 후 1~5 재통과
7. 브라우저 콘솔에 CORS 오류 부재
8. `nmap <EIP>` 기준 개방 포트가 80·443 뿐(22·5432·6379·9092 폐쇄)
9. `pg_dump`·학생 사진 백업이 S3(`db/`·`photos/`)에 적재, 복구 리허설 1회 성공(§7)

**최초 배포는 10분 이상 걸릴 수 있다.** 이미지 5종을 처음 받고(백엔드 ~400MB + 인프라 ~700MB) backend 의 `start_period`(90s)와 Flyway 마이그레이션이 순차로 붙는다. 워크플로의 상태 판정 상한은 이를 감안한 20분(`MAX_WAIT_SECONDS=1200`) — 진행 중인데 실패로 오판해 운영자가 재실행하면 중복 배포가 큐에 쌓인다(`concurrency: cancel-in-progress: false`).

---

## 3. SSM 파라미터 목록

파라미터 경로 접두사는 `/school-bus/demo/` — `infra/scripts/deploy.sh` 의 `PARAM_PREFIX="/school-bus/demo"` 와 일치해야 한다. 아래 **필수 11개**는 `deploy.sh` 가 `get_param` 으로 반드시 조회하는 이름이다(하나라도 빠지거나 값이 비어 있으면 에러를 stderr 에 남기고 배포가 그 자리에서 실패 — 조용히 넘어가지 않는다. 실패한 배포는 기존 `/opt/school-bus/.env` 를 건드리지 않는다).

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
| `/school-bus/demo/SPRING_PROFILES_ACTIVE` | String | **`prod` 또는 `demo`** — 다른 값이면 배포 중단. 기본값이 없다: 예전에는 compose 기본값 `demo` 라 이 값이 빠진 배포가 가짜 시드 + 가짜 버스(`DemoRunSimulator`)로 조용히 떴다. `prod` = 실 운영(계정 0개 · 첫 관리자는 아래 선택 항목), `demo` = 데모 시드 |
| `/school-bus/demo/GRAFANA_ADMIN_PASSWORD` | SecureString | `openssl rand -base64 24`. 운영 Grafana 초기 관리자(`admin`) 비밀번호 — §11. 기본 비밀번호로 뜨지 않게 필수 |

**`prod` 일 때만 필수인 3개 — FCM 푸시(`Ruling 331`)** — `FCM_PROJECT_ID`(String) · `FCM_CLIENT_EMAIL`(String) · `FCM_PRIVATE_KEY`(SecureString, 서비스 계정 JSON 의 `private_key` — 줄바꿈이 `\n` 문자여도 받는다). Firebase 프로젝트 생성·서비스 계정 키 발급은 사용자 작업. `SPRING_PROFILES_ACTIVE` 가 `prod` 인데 셋 중 하나라도 없으면 `deploy.sh` 가 1단계에서 멈춘다(앱을 띄워 기동 실패를 보는 것보다 컨테이너를 건드리기 전에 막는 편이 낫다). `demo` 는 읽지 않는다(로그 전용 발송).

**선택 항목 — 없으면 컨테이너가 `application.yml` 기본값으로 뜬다**(`deploy.sh` 는 `ParameterNotFound` 만 "없음"으로 보고, 권한 오류·네트워크 오류는 선택 항목이어도 배포를 멈춘다 — 못 읽은 값을 없는 값으로 취급하면 운영이 조용히 기본값으로 뜬다). 필수 계약에 섞지 않으려고 이름을 따로 모은다.

| 이름 | 타입 | 값/생성법 |
|---|---|---|
| `NAVER_SEARCH_CLIENT_ID` · `NAVER_SEARCH_CLIENT_SECRET` | SecureString | NCP 콘솔 → NAVER API HUB → Application(지도 키와 **다른** 애플리케이션). 없으면 장소 이름 검색이 401 로 실패해 주소 후보만 나간다 |
| `NAVER_DIRECTIONS_PATH` · `NAVER_DIRECTIONS_MAX_POINTS` | String | **짝** — 하나만 있으면 배포 중단(`Ruling 361`). Directions 15 일일 한도(3,000)가 바닥났을 때 Directions 5 로 전환: `/map-direction/v1/driving` + `7`. 되돌리려면 두 파라미터를 함께 삭제하고 재배포 |
| `BOOTSTRAP_ADMIN_LOGIN_ID` · `BOOTSTRAP_ADMIN_PASSWORD_HASH` | String · SecureString | **짝** — 첫 메인 관리자(`prod` 첫 배포용, 아래). 해시는 §4 절차로 만든 bcrypt 해시이며 평문이면 배포가 멈춘다 |

`PHOTO_STORAGE_ROOT` 는 SSM 으로 열지 않는다 — compose 가 named volume 마운트 경로(`/app/var/photos`)와 같은 값으로 고정한다. 어긋나면 사진이 컨테이너 안에만 쌓여 재배포마다 사라진다.

**첫 메인 관리자(`prod`).** `prod` 는 계정이 0개이고 가입 API 로는 메인 관리자를 만들 수 없다. 앱이 기동할 때 `FirstSystemAdminBootstrap` 이 **메인 관리자가 하나도 없을 때만** 위 두 값으로 활성 계정 1개를 만든다. 이미 있으면 아무것도 하지 않는다(값은 무시 — 첫 배포 뒤 남아 있어도 된다). 값이 잘못됐으면 기동이 멈추고 로그에 이유가 남는다(평문 비밀번호 · 한쪽만 있음 · 이미 쓰는 아이디). 로그에는 비밀번호·해시를 남기지 않는다. 절차: ① §4 로 해시 생성 ② 두 파라미터 등록 ③ 배포 ④ 로그인 ⑤ **비밀번호 변경 후 두 파라미터 삭제**(값이 SSM 에 남는 시간을 줄인다). 로그인이 안 되면 `docker compose logs backend | grep BOOTSTRAP` 로 거부 사유를 본다.

**⚠️ NCP 키가 없어도 위 두 항목은 등록해야 한다.** `deploy.sh` 의 `get_param` 은 필수 11개 전부를 필수로 보고, 파라미터가 없거나 값이 비면 **1단계에서 배포를 중단**한다(`ROUTING_PROVIDER=osrm` 만 등록하고 두 키를 비워두면 배포 자체가 진행되지 않는다). `osrm` 폴백을 쓰려면 두 항목에 `unused` 같은 임의 문자열을 넣어 등록하고 `ROUTING_PROVIDER` 를 `osrm` 으로 둔다 — `osrm` 일 때 앱은 이 두 값을 읽지 않는다. 필수 계약을 단순하게 유지하려는 의도적 설계다 — 그래서 새로 늘린 값 중 없어도 되는 것은 위 "선택 항목" 표에 따로 모으고, 필수 표에는 섞지 않았다(섞으면 어떤 값이 비어도 되는지가 스크립트·문서·compose 세 곳에서 갈린다). `SEED_PASSWORD_HASH` 는 `prod` 에서 쓰이지 않지만 같은 이유로 계속 필수다(임의의 bcrypt 해시를 등록).

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

같은 방식으로 **첫 메인 관리자 해시**(`BOOTSTRAP_ADMIN_PASSWORD_HASH`, §3)를 만든다. 결과를 SSM 에 등록할 때는 셸이 `$` 를 변수로 확장하지 않게 작은따옴표로 감싼다. `deploy.sh` 는 `$2a|b|y$NN$` + 53자 형식이 아니면 컨테이너에 닿기 전에 배포를 멈춘다(평문 비밀번호를 넣은 실수 방지 — 그 값이 출력에 찍히지 않는다).

---

## 5. 일상 배포

`deploy-backend.yml` 은 **수동 실행 전용**이다(`workflow_dispatch` — 2026-09-09). `main` push 로 배포되지 않는다. 실행 흐름은 테스트 → 이미지 빌드 → ECR → SSM Send Command → EC2 배포. 웹 배포 워크플로(`deploy-web.yml`)는 제거된 상태이며 배포 구성은 다시 만든다.

수동 트리거: GitHub 저장소 Actions 탭 → `deploy-backend` 선택 → `Run workflow`.

### 5.1 CI — `ci.yml` (2026-10-01)

`main` push · 모든 PR 에서 도는 **검증 전용** 워크플로. 배포·시크릿과 무관하다.

| job | 실행 내용 | 제외 |
|---|---|---|
| `backend` | compose 의 postgres(`max_connections=300`)를 띄우고 전용 DB 를 만든 뒤 `./gradlew test -PtestDbUrl=… -PciQuiet`. Redis 는 시험이 Testcontainers 로 붙임 | `@Tag("live")` 실 네이버 API 시험 |
| `web` | `scripts/verify.sh web` — `next typegen` · `tsc --noEmit` · `lint` · `vitest`(Node 22) | 파일명 `*realBackend*.test.ts`(실서버 계약 시험) |
| `flutter` | `scripts/verify.sh flutter` — 4개 패키지의 `pub get` · `build_runner`(있는 곳) · `analyze` · `test`(3.44.8) | `@Tags(['real_backend'])` 시험 |

- 바뀐 모듈의 job 만 돈다(`dorny/paths-filter`). `ci.yml` 이 바뀌면 전부 돈다. 같은 PR 의 새 커밋은 앞선 실행을 취소한다
- 로컬 재현: `scripts/verify.sh`(전부) · `scripts/verify.sh web flutter`(골라서). 백엔드는 `backend/scripts/test.sh` 가 전용 DB 를 만들고 지움
- **로그 정책 — 저장소가 공개라 Actions 로그도 공개다.** 비밀값 없이 돈다(네이버 키 불필요 — `build.gradle` 이 지오코딩·경로·장소검색을 stub 으로 고정). 백엔드는 `-PciQuiet` 으로 로그 수준을 WARN 으로 낮추고 결과 XML 에서 stdout·stderr 를 뺀다 — 실패 때 남는 것은 시험 이름·실패 요약뿐. `deploy-backend.yml` 도 실패 시 backend 컨테이너 로그 120줄을 찍지 않고 `deploy.sh` 가 쓴 실패 사유 줄만 남긴다(로그는 EC2 에서 확인)
- 결과 XML 은 artifact `backend-test-results`(7일)로 남는다
- 실 네이버 API 시험은 CI 에서 돌지 않는다 — 자격증명이 없고 Directions 일일 한도가 있다. 실행법은 `CLAUDE.md` "Build & run"
- 의존성·이미지·액션 갱신은 `.github/dependabot.yml`(주 1회 · 생태계별 묶음)이 PR 로 올린다. Docker 이미지 태그는 부 버전까지 고정

동시 배포 처리: 백엔드는 `concurrency: cancel-in-progress: false` — 겹치면 취소 대신 줄을 세운다(옛 이미지가 새 이미지를 덮어쓰는 사고 방지).

`.htpasswd` 는 `infra/**` 배포에 영향받지 않는다 — `deploy-backend.yml` 의 두 `s3 sync` 가 `--exclude "proxy/.htpasswd"` 로 이 파일을 동기화·삭제 대상에서 제외한다(§2.12). 배포마다 재업로드는 불필요.

---

## 6. 롤백

이전 커밋 SHA 로 되돌리는 법. Actions 재실행보다 EC2 에서 직접 돌리는 편이 빠르다(빌드를 다시 하지 않고 이미 ECR 에 있는 과거 태그를 그대로 재사용).

```bash
# EC2 에서 (SSM Session Manager 접속 후)
sudo /opt/school-bus/infra/scripts/deploy.sh <이전_커밋_SHA>
```

**`<이전_커밋_SHA>` 는 40자 full SHA 다.** 워크플로가 `${{ github.sha }}`(40자)로 태그를 붙여 push 하므로, GitHub 화면에서 흔히 보이는 short SHA(7자)로 부르면 ECR 에 그런 태그가 없어 `pull` 단계에서 막힌다. `git rev-parse <short>` 로 펼쳐 쓴다.

`<이전_커밋_SHA>` 는 ECR 에 그 태그의 이미지가 아직 남아 있어야 동작한다 — §2.5 수명주기로 **최근 10개**만 남으므로 롤백 후보는 그 안에서만 고른다. EC2 쪽은 `deploy.sh` 가 성공 뒤마다 안 쓰는 이미지를 지운다(`docker image prune -af --filter until=72h` — 이미지 **생성** 시각이 72시간보다 오래된 것만이고, 컨테이너가 쓰는 이미지는 남는다). 그래서 오래전에 빌드된 직전 이미지는 EC2 에 없을 수 있고, 그 태그로의 롤백은 ECR 에서 다시 받는다(수십 초). `deploy.sh` 는 **백엔드 이미지 태그만 되돌린다** — `infra/`·`docker-compose.prod.yml` 자체는 EC2 에 이미 동기화된 최신 상태 그대로 유지된다. 즉 인프라 설정까지 과거로 되돌리려면 별도로 그 시점의 파일을 S3/EC2 에 다시 올려야 한다.

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

### 7.2 학생 사진 복원

`backup-db.sh` 가 DB 덤프와 함께 사진 묶음(`photos/<날짜시각>.tar.gz`)을 올린다(매번 전체). 복원은 backend 컨테이너가 떠 있는 상태에서 한 줄이다 — 묶음 안 경로가 `photos/...` 라 `/app/var` 에 풀면 named volume(`photo-data`)의 제자리로 들어간다.

```bash
sudo aws s3 cp s3://<백업버킷>/photos/<날짜시각>.tar.gz - \
  | sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env \
    exec -T backend tar xzf - -C /app/var
```

같은 이름의 파일은 덮어쓰고 없는 파일은 되살린다(지우지는 않는다). 사진이 수 GB 를 넘으면 매번 전체를 올리는 방식을 `aws s3 sync` 로 바꾼다(`backup-db.sh` 주석).

---

## 8. 장애 대응

증상별 확인 명령 · 조치 · 기록 대상. `TECH_DECISIONS §14.2`(실패 모드 7행)·`§14.3`(수동 개입 4행)과 이 절 원래 7행을 한 표로 통합.

| 증상 | 확인 명령 | 조치 | 남길 것 |
|---|---|---|---|
| 로그인 401 반복 | `.env` 의 `SEED_PASSWORD_HASH` 값과 실제 비밀번호 해시 일치 여부 확인 | 해시 재발급 후 SSM 파라미터 갱신, `deploy.sh` 재실행 | 재발급 사유·시각 |
| 브라우저 CORS 오류 | `CORS_ALLOWED_ORIGINS` 값에 스킴 포함 정확한 출처 존재 여부 확인 | 누락 출처 추가 후 SSM 갱신·재배포 | 추가한 출처 값 |
| WebSocket 만 연결 실패(REST 는 정상) | `WS_ALLOWED_ORIGIN_PATTERNS` 값과 nginx `/ws/` 블록 Upgrade 헤더 확인 | 패턴 또는 nginx 설정 수정 후 `proxy` 컨테이너 재기동 | 수정한 패턴·설정 값 |
| 배차·시뮬레이션 500 | NCP 키 유효성, `ROUTING_PROVIDER` 값 확인 | 키 재발급 또는 `ROUTING_PROVIDER=osrm` 폴백 전환(전환 시에도 `NAVER_DIRECTIONS_KEY_ID`·`NAVER_DIRECTIONS_KEY` 는 임의 값 등록 필수 — 비어 있으면 다음 배포가 `deploy.sh` 1단계에서 중단) | 전환 여부·사유 |
| 컨테이너 반복 종료 | `free -h` 로 메모리, `docker stats`, 스왑 활성 여부 확인 | 스왑 추가 또는 인스턴스 사양 상향 | 종료 시점·메모리 수치 |
| 인증서 만료 | `docker compose logs certbot`, 80 포트 개방 여부 확인 | certbot 갱신 재시도, 방화벽 규칙 수정 | 갱신 결과 |
| Swagger UI 401 · 기동 실패 | §2.12 절차 확인 | EC2 에서 `.htpasswd` 재생성 후 `proxy` 컨테이너 재기동 | 재생성 시각 |
| 지도 API 장애(노선 계산 ③단계 영향) | `resilience4j_circuitbreaker_state{name="geocoding"\|"mapRoute"}` 값 확인(관측 목표 8) | 자동 폴백 — 직선거리 근사로 배차 유지, 화면에 폴백 사실 표시, 배치는 계속 진행. 서킷 닫히면 다음 회차부터 정상, 이미 배포된 노선은 재계산 제외 | 폴백 지속 시간 |
| Redis 장애(최신 좌표·캐시 영향) | `docker compose logs redis`, `redis-cli ping`, 지표 `schoolbus.position.fallback` 증가 | 자동 대체 — 위치 조회 3종(학부모 버스 위치 · 관계자 관제 · 메인 관리자 관제)과 비상 신고 위치는 DB 이력 최신 행으로 조회(현재 정차지 이름은 비어 나감). 근접·출발 판정은 그 틱을 건너뛰고 다음 틱에 다시 본다. 캐시 미스로 계산 반복 | 장애 지속 시간 |
| DB 장애(전면 영향) | `docker compose ps postgres`, 헬스체크 UP 여부 확인 | 매니저 앱은 오프라인 큐로 승하차만 지속, 나머지 제품은 조회 불가. 복구 후 큐 동기화(멱등, `client_key` UNIQUE) | 장애 시작·복구 시각 |
| 앱 인스턴스 재시작(WS 끊김·배치 중단) | `docker compose ps backend`, 재시작 로그 확인 | 자동 복구 — 클라이언트 자동 재접속, 배치는 폴링이라 놓친 회차를 다음 틱에 자동 회수. 별도 절차 부재 | 재시작 원인 |
| 배치 밀림(확정 지연) | `schoolbus_run_confirmation_lag_seconds` 값 확인(관측 목표 8 — 값이 계속 늘면 워커 수·인스턴스 증설 신호) | 도래분은 다음 틱으로 자동 이월. 지속되면 워커 수 증설, 그다음 인스턴스(§3) | 지연 수치 추이 |
| 확정 실패한 회차(그 회차만 노선 부재) | Prometheus `RunUnconfirmed` 경보(§11 · 확정 시각을 5분 넘긴 idle 회차 > 0 이 1분 유지) · `schoolbus_run_confirmation_retry_failures_total` 값 확인(관측 목표 8) | `idle` 복귀 후 자동 재시도. 이 경보가 **1차 방어**다 — 수신 채널이 아직 없어(§11) Prometheus `/alerts` 를 직접 봐야 하고, 채널 연결 전에는 외부 감시(`/healthz`)가 서버 사망만 알린다. 아래 행으로 이어짐 | 실패 회차 id·연속 실패 횟수 |
| 확정이 계속 실패하는 회차(경보 이후) | 관제 화면의 회차 `consecutive_failures`(`API_SPEC §6.14` 대상 식별) | **강제 확정**: 메인 관리자 웹 '회차 강제 확정' 화면 또는 `POST /admin/runs/{runId}/force-confirm`(`API_SPEC §6.14`, `Ruling 254`) — 폴백(직선거리) 계산으로 배포한다. 사유(`reason`)가 필수이고 `audit_log` 에 누가·언제·왜·폴백 여부가 남는다 | 강제 확정 사유·회차 id(`audit_log` 의 `detail.action=run.force_confirm`) |
| 디스크 80% 경보(`HostDiskAlmostFull`) | `df -h /` · `docker system df` · 사진 볼륨 크기(`docker compose exec backend du -sh /app/var/photos`) | 안 쓰는 이미지 정리(`docker image prune -af --filter until=72h`) · 로그 · 사진 백업 확인. 부족하면 EBS 볼륨 확장(`DEPLOYMENT.md §2.4` — 디스크 30GB 는 산정 근거가 없는 값) | 사용률·정리한 항목 |
| 자동 거절이 잘못 나감 | `change_request` 상태와 자동 거절 이력 확인 | 되돌리기 수단 부재 — 관계자가 ①구간 경로로 다시 처리 | 자동 거절 이력 그대로 보존 |
| 하원 종료 보류가 안 풀림 | 미하차 학생 목록 확인 | 동승자에게 처리 요청. 강제 종료 경로는 두지 않음(미하차 상태로 회차를 끝내는 경로가 생기면 `RUN-06` 우회 가능 — 종료 보류는 결함이 아니라 아직 안 내린 아이가 있다는 신호) | 처리 요청 시각·대상 학생 |
| 운행 시작 ±10분 창을 놓침 | 해당 없음 — `X-01`(Ruling 202)로 창을 ±10분으로 확장, 예외 절차 자체를 폐지 | 없음(정상 처리 경로로 흡수) | 없음 |

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
| 경보 수신 채널 미연결 · APM 부재 | 경보 규칙과 Alertmanager 자리는 있으나(§11) 수신 채널이 미정이라 발화한 경보는 Prometheus `/alerts` 에서만 보인다. 서버 사망은 외부 감시(`/healthz`)를 걸어야 알 수 있다 |

---

## 11. 운영 관측 (2026-10-01 · R46 ops)

운영 compose 에 `prometheus` · `alertmanager` · `node-exporter` · `grafana` 가 함께 뜬다. 설정·경보 규칙·대시보드 5종은 개발용 `docker-compose.app.yml` 과 같은 `infra/observability/` 파일이다(운영 전용은 `prometheus/prometheus.prod.yml` 과 `alertmanager/alertmanager.yml`).

| 항목 | 값 |
|---|---|
| 지표 보존 | 15일(`prometheus-data` 볼륨 — 컨테이너를 다시 만들어도 남는다) |
| 메모리 한도 | prometheus 512m · grafana 256m · alertmanager 96m · node-exporter 64m (합 928MB) |
| 외부 노출 | **없음** — Prometheus(`127.0.0.1:9090`)·Grafana(`127.0.0.1:3001`)는 호스트 loopback 에만 바인딩하고, exporter·Alertmanager 는 컨테이너 네트워크 안에서만 닿는다. 보안 그룹은 80·443 뿐이다 |
| 운영에 없는 것 | postgres-exporter · redis-exporter — 그래서 대시보드 `3. 데이터 계층` 의 postgres·redis 패널은 비어 있다 |

⚠ **메모리.** 한도의 합 928MB 는 backend 한도(3GB)와 합치면 `t3.medium`(4GB)을 넘는다. 한도는 상한이지 예약이 아니고 실사용은 대략 300MB 로 추정하지만(측정 전) 배포 순간 신·구 backend 가 함께 살아 있는 시점에는 스왑 2GB(§2.8)에 기댄다. 관측을 켠 채 안정적으로 운영하려면 권장 사양(4 vCPU · 8GB — 2026-09-09 부하 한계 측정 §13 · `Ruling 351`)으로 올린다.

### 11.1 접근 — SSM 포트 포워딩

22번 포트를 열지 않으므로 SSH 터널 대신 SSM 포트 포워딩을 쓴다(Session Manager 플러그인이 로컬에 필요).

```bash
# Grafana → http://localhost:3001 (admin / SSM 의 GRAFANA_ADMIN_PASSWORD)
aws ssm start-session --target "$INSTANCE_ID" --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3001"],"localPortNumber":["3001"]}'

# Prometheus → http://localhost:9090/alerts (발화 중인 경보)
aws ssm start-session --target "$INSTANCE_ID" --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["9090"],"localPortNumber":["9090"]}'
```

Grafana 는 볼륨이 없어 컨테이너를 다시 만들 때마다 SSM 값으로 초기화된다(저장할 사용자·설정이 없다). 익명 접근과 회원 가입은 꺼져 있다.

### 11.2 경보 규칙

규칙은 `infra/observability/prometheus/alerts.yml` 이고, 조건식·`for` 는 `alerts.test.yml`(promtool 단위 시험)이 가짜 시계열로 검사한다. `TECH_DECISIONS §13.4` 표와 행 단위로 대응한다.

| 경보 | 조건 | 등급 | §13.4 |
|---|---|---|---|
| `RunUnconfirmed` | `schoolbus_run_unconfirmed > 0` 이 1분 유지 | 즉시(critical) | 1행 |
| `NoShowEscalationFailing` · `NoShowEscalationStalled` | 미승차 에스컬레이션 실패 · 180초 초과 미성공 | 즉시(critical) | 2행 |
| `RunPositionLost` | `schoolbus_run_position_lost > 0` 이 1분 유지 | 경고 | 3행 |
| `PushDeliveryFailing` | 최근 10분 안에 `schoolbus_notification_push_failures_total` 증가 | 경고 | 5행 |
| `HostDiskAlmostFull` | 루트 디스크 사용률 80% 초과가 10분 유지 | 경고 | (표 밖 — 디스크가 차면 postgres 쓰기가 실패해 전면 정지) |

4행(지도 API 서킷 open)과 6행(배치 지연 p95)은 아직 규칙이 없다. `PushDeliveryFailing` 은 "율"이 아니라 건수다 — 분모(시도 수) 지표가 없고, 있는 지표는 재시도를 전부 소진해 `failed` 로 굳은 건수뿐이다.

### 11.3 수신 채널 — 미정

Alertmanager 는 떠 있지만 receiver 가 `unrouted`(수신자 없음)라 **어디로도 알리지 않는다.** 채널(Telegram·Slack·이메일 중)이 정해지면 `alertmanager.yml` 의 receiver 를 채운다 — 봇 토큰·웹훅 URL 은 저장소에 적지 않고 SSM 으로 넘기는 방식을 그때 정한다. 그 전까지 발화한 경보는 §11.1 의 Prometheus `/alerts` 에서만 보인다.

### 11.4 외부 가동 감시 — `/healthz`

`https://api.<도메인>/healthz` 는 인터넷에서 닿는 유일한 헬스 주소다(UptimeRobot 같은 외부 감시용). backend 의 `/actuator/health` 를 그대로 프록시하므로 응답은 `{"status":"UP"}` 뿐이고(상세는 `show-details: never`), backend 나 DB·Redis 가 DOWN 이면 503, backend 가 죽으면 502·504 라 외부 감시가 실패를 본다. 정적 200 이 아니다 — 정적 200 은 backend 가 죽어도 초록이다. IP 당 분당 30회로 제한한다(초과 429). `/actuator` 의 나머지(`prometheus` 등)는 계속 404 다.

⚠ EC2 가 통째로 죽으면 Prometheus·Alertmanager 도 함께 죽는다 — 서버 사망은 이 외부 감시로만 알 수 있다(등록은 사용자 작업).
