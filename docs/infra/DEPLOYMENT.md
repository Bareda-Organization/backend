# School-Bus 배포·운영 절차서

실제 AWS 계정에서 **그대로 따라 배포**하는 절차서. `<계정ID>`·`<도메인>`·`<배포버킷>`·`<백업버킷>`·`<인스턴스ID>` 등 꺾쇠 값은 배포자가 직접 정하는 값 — 미완성이 아니라 정상이다.

> **관계자 웹은 Vercel 에서 배포한다**(2026-10-01 `Ruling 481` · 절차 §12). 옛 `deploy-web.yml`(GitHub Actions → Vercel prebuilt)은 2026-09-09 삭제 상태 그대로 두고 되살리지 않는다 — Vercel 의 GitHub 연동이 push 로 직접 빌드·배포한다. 모바일(스토어) 경로(§9)는 옛 Flutter 기준의 **보존 절차**이며 새 앱 2종(`frontend/apps/manager-app` · `parent-app`)의 스토어 절차는 아직 쓰지 않았다. 실행 가능한 경로는 **API·인프라(EC2 + `deploy-backend.yml`)** 와 **웹(Vercel)** 이다.

**전제 조건**: AWS 계정(리소스 생성 권한), 도메인 1개(신규 구입도 가능), GitHub 저장소 쓰기 권한, Vercel 계정, 로컬에 AWS CLI v2 설치·`aws configure` 완료, 로컬에 Docker(BCrypt 해시·htpasswd 생성용). 웹 배포(§12)에는 **Vercel 계정**과 같은 등록 도메인의 DNS 편집 권한이 필요하다.

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

- **웹**: `frontend/apps/academy-web`(Next.js) → Vercel 의 GitHub 연동이 `main` push 마다 빌드·배포(§12). GitHub Actions 워크플로 없음 — `deploy-web.yml` 은 2026-09-09 삭제 상태 그대로이며 되살리지 않는다. 운영 compose·nginx 에 웹 컨테이너가 없다(`Ruling 481`)
- **API·인프라**: `backend/` + `docker-compose.prod.yml` → GitHub Actions 가 이미지 빌드해 ECR 에 올리고 SSM 으로 EC2 에 배포(`.github/workflows/deploy-backend.yml`)
- ~~**모바일**: `frontend/` → 로컬에서 수동 빌드 후 스토어 콘솔에 직접 업로드(§9)~~ — **2026-09-09 삭제, 실행 불가**

**⚠️ 단일 인스턴스 제약 — 백엔드 인스턴스를 늘리면 안 된다.** `docker-compose.prod.yml`(backend 서비스 주석)·`infra/proxy/nginx.prod.conf`(`upstream backend_pool` 주석)에 동일한 경고가 있다. 이유 3가지:

1. `@Scheduled` 4개(위치 시뮬레이션·연결끊김·근접/미승차·SOS 에스컬레이션)가 인스턴스마다 중복 실행돼 같은 알림이 여러 번 발송
2. ~~버스 위치가 `InMemoryBusLocationRepository` — 인스턴스 간 공유 불가~~ → **해소**(2026-08-21, `RedisBusLocationRepository`). **단 이것만으로 증설 조건은 성립하지 않는다** — 1·3이 그대로 남아 금지는 유지한다
3. WebSocket STOMP 세션이 인스턴스에 고정 — Redis 브로커 릴레이 없이는 로드밸런싱 불가

해소하려면 위 3가지를 먼저 손봐야 한다. 상세는 설계 문서 §4.2 참조.

---

## 2. 최초 구축

### 2.1 리소스 생성 순서 요약

VPC 기본 사용 → EC2(+ 데이터 EBS · EIP) → ECR → S3 버킷 2개 → IAM 인스턴스 역할 → 일일 스냅샷 정책(§2.4.1) → IAM OIDC 역할 → SSM 파라미터 필수 10개 → EC2 부트스트랩 → 도메인 연결 → 인증서 발급 → nginx 설정 치환 → **`.htpasswd` 생성(EC2)** → GitHub 시크릿 등록 → 최초 배포 → **첫 백업 + 복구 연습(§7)**. 웹(Vercel)은 §12 를 따로 진행한다.

**⚠️ 순서가 중요한 지점 셋**: (a) `api` A 레코드(§2.9)를 인증서 발급(§2.10)보다 **먼저** 끝내야 한다 — HTTP-01 챌린지가 도메인을 조회해 EIP 로 접속하므로 A 레코드가 없으면 최초 발급이 실패한다. (b) 그 인증서는 `docker compose up`(proxy 포함)을 **한 번도 돌리기 전에** 발급해야 한다 — 인증서가 없으면 nginx 의 443 블록이 기동 자체를 못 해 순환 의존이 생긴다. (c) `docker-compose.prod.yml` 의 proxy 서비스가 요구하는 `infra/proxy/.htpasswd` 는 `.gitignore:26` 에 등록된 비밀 파일이다 — 저장소에도 배포용 S3 버킷에도 두지 않고 **EC2 에 직접 1회 생성**한다(이유·절차는 §2.12). 최초 배포(§2.14) 전에 반드시 끝낼 것.

리전은 `ap-northeast-2`(서울)로 고정 — `deploy.sh`·`backup-db.sh`·`docker-compose.prod.yml`(`awslogs-region` 기본값)·`deploy-backend.yml`(`env.AWS_REGION`)이 모두 이 값을 기본값으로 쓴다. 웹(Vercel)은 AWS 를 호출하지 않아 region 개념이 없다.

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
  --block-device-mappings '[
    {"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3"}},
    {"DeviceName":"/dev/sdf","Ebs":{"VolumeSize":20,"VolumeType":"gp3","DeleteOnTermination":false}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=school-bus-demo}]' \
  --query 'Instances[0].InstanceId' --output text)

# 데이터 디스크(/dev/sdf)에 이름과 스냅샷 대상 표식을 붙인다(§2.4.1)
aws ec2 wait instance-running --instance-ids "$INSTANCE_ID"
DATA_VOL_ID=$(aws ec2 describe-volumes \
  --filters Name=attachment.instance-id,Values="$INSTANCE_ID" Name=attachment.device,Values=/dev/sdf \
  --query 'Volumes[0].VolumeId' --output text)
aws ec2 create-tags --resources "$DATA_VOL_ID" --tags Key=Name,Value=school-bus-data Key=Snapshot,Value=daily
echo "데이터 볼륨: $DATA_VOL_ID"

ALLOC_ID=$(aws ec2 allocate-address --domain vpc --query 'AllocationId' --output text)
aws ec2 associate-address --instance-id "$INSTANCE_ID" --allocation-id "$ALLOC_ID"
EIP=$(aws ec2 describe-addresses --allocation-ids "$ALLOC_ID" --query 'Addresses[0].PublicIp' --output text)
echo "인스턴스 ID: $INSTANCE_ID / 고정 IP: $EIP"
```

Elastic IP 를 붙이는 이유 — 인스턴스를 재시작(`stop`/`start`)하면 퍼블릭 IP 가 바뀐다. 도메인 A 레코드가 가리키는 대상이 계속 유효하려면 고정 IP 가 필요하다. `t3.medium`(4GB) 선정 근거는 설계 문서 §2.8(메모리 사이징 표) 참조. 루트 디스크 30GB 는 설계 문서에 별도 산정 근거가 없는 값 — 부족 시 EBS 볼륨 확장으로 대응.

**데이터 디스크(`/dev/sdf` · 20GB)를 따로 두는 이유(`Ruling 500`).** DB·학생 사진·Prometheus 지표·인증서가 전부 Docker named volume 이라, 같은 루트 디스크에 두면 인스턴스를 종료할 때(기본값 `DeleteOnTermination=true`) 함께 사라지고 남는 것은 덤프뿐이다. 이 디스크는 종료 때 삭제되지 않게(`false`) 만들어 새 인스턴스에 다시 붙일 수 있다(§7.3). 크기 20GB 는 DB 5년 6~9GB(`DB 용량·수평 확장` 실측) + 사진 + 지표 15일 + 여유의 **추정**이다 — 부족하면 볼륨 확장 뒤 `xfs_growfs /var/lib/docker/volumes`. 부트스트랩이 이 디스크를 `/var/lib/docker/volumes` 에 XFS 로 마운트한다(§2.8). 디스크 장치 이름은 Nitro 인스턴스(t3)에서 `/dev/nvme1n1` 로 보인다.

### 2.4.1 일일 스냅샷 — DLM 정책

매일 데이터 디스크의 EBS 스냅샷을 만들고 7개를 보관한다. 덤프(S3)가 손상됐을 때의 2차 방어선이며, DB 뿐 아니라 사진·인증서·지표까지 하루 전 상태로 되돌린다. 스냅샷은 실행 중인 디스크의 **충돌 일관성** 복사본이라 Postgres 가 복구 시 WAL 을 되감아 정상 기동한다.

```bash
aws dlm create-default-role --resource-type snapshot   # 계정에서 처음 한 번 — 이미 있으면 오류, 무시

cat > dlm-policy.json <<'JSON'
{
  "ResourceTypes": ["VOLUME"],
  "TargetTags": [{"Key": "Snapshot", "Value": "daily"}],
  "Schedules": [{
    "Name": "school-bus-daily",
    "CreateRule": {"Interval": 24, "IntervalUnit": "HOURS", "Times": ["18:00"]},
    "RetainRule": {"Count": 7},
    "CopyTags": true
  }]
}
JSON
aws dlm create-lifecycle-policy --description "school-bus 일일 스냅샷" --state ENABLED \
  --execution-role-arn arn:aws:iam::<계정ID>:role/AWSDataLifecycleManagerDefaultRole \
  --policy-details file://dlm-policy.json
```

`18:00` 은 UTC 라 한국 시각 03:00 이다. 다음 날 03:00 이후 `aws ec2 describe-snapshots --owner-ids self --filters Name=tag:Snapshot,Values=daily --query 'Snapshots[].[SnapshotId,StartTime,State]'` 에 스냅샷이 보이는지 확인한다.

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

§3 의 표를 그대로 따라 **필수 10개**(+ `prod` 면 FCM 3개)를 등록한다. 선택 항목은 필요할 때 등록한다. 시드 비밀번호 해시 생성은 §4 참조.

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
sudo BACKUP_BUCKET=<백업버킷> bash bootstrap-ec2.sh      # 데이터 디스크가 /dev/nvme1n1 이 아니면 DATA_DEVICE=/dev/... 도 준다(lsblk)
```

`bootstrap-ec2.sh` 가 하는 일(주석 근거): ① **데이터 디스크를 `/var/lib/docker/volumes` 에 마운트**(비어 있으면 XFS 로 포맷, 이미 파일시스템이 있으면 포맷하지 않고 그대로 — Docker 설치보다 먼저), docker·`cronie` 설치, compose v2 플러그인 설치, `/opt/school-bus` 디렉터리 생성, 스왑 2GB(배포 순간 신·구 컨테이너 동시 실행 시 OOM 방지), 백업 크론 등록(**DB 매시 정각 · 사진 매일 03:10**, `/etc/cron.d/schoolbus-backup`) + 백업 성공 시각 지표 폴더(`/var/lib/node_exporter/textfile`) 생성, `crond` 활성 확인.

`cronie` 를 명시적으로 설치하는 이유 — Amazon Linux 2023 은 cron 을 기본 포함하지 않는다(AWS 는 systemd timer 대체를 권고). 없는 채로 두면 백업이 조용히 영원히 미실행. 스크립트 마지막의 `systemctl is-active crond` 검사가 그 상태로 완료 처리되는 것을 막는다.

### 2.9 도메인 연결

- `api.<도메인>` → A 레코드 → §2.4 의 Elastic IP
- `app.<도메인>` → Vercel 프로젝트의 **커스텀 도메인**으로 연결한다(§12.4). **API 와 같은 등록 도메인**이어야 한다 — 로그인 쿠키가 `SameSite=Strict` 라 Vercel 기본 주소(`*.vercel.app`)로는 갱신이 되지 않는다(§12.2)

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

`infra/proxy/nginx.prod.conf` 에 `api.example.com`(4곳 — `server_name` 2곳, `ssl_certificate`·`ssl_certificate_key` 각 1곳)이 자리표시자로 박혀 있다. 웹 주소(`app.<도메인>`)는 이 파일에 없다 — 웹은 Vercel 이고 허용 출처는 SSM(§3)이 갖는다. nginx 는 환경변수를 못 읽으므로 파일 자체를 고쳐 커밋한다.

```bash
sed -i '' 's/api\.example\.com/api.<도메인>/g' infra/proxy/nginx.prod.conf
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
웹(Vercel)은 GitHub Secrets 가 필요 없다 — Vercel 이 GitHub 연동으로 직접 배포하고 환경변수는 Vercel 프로젝트에 둔다(§12.3). 옛 `deploy-web.yml` 용 `API_BASE_URL`·`VERCEL_*` 항목은 쓰지 않는다.

### 2.14 최초 배포·검증

§2.11 커밋을 push 한 뒤 Actions 탭에서 `deploy-backend.yml` 을 `workflow_dispatch` 로 실행하면 최초 배포가 실행된다(백엔드는 push 자동 배포가 없다. 웹은 Vercel 이 push 로 자동 배포한다 — §12). 검증 기준(설계 문서 §8 의 9개 + 운영 2차의 1개, 전부 통과해야 완료로 간주):

1. 컨테이너 내부 `/actuator/health` 가 `UP`(DataSource·Redis 포함). 외부에서는 `/actuator` 가 404 이고 `https://api.<도메인>/healthz` 만 200(`{"status":"UP"}` — 외부 가동 감시가 칠 주소, §11)
2. `https://app.<도메인>`(Vercel, §12)에서 로그인 성공 — `demo` 는 데모 계정, `prod` 는 첫 메인 관리자(§3 "첫 메인 관리자")
3. 관리자 관제 화면에서 버스 마커가 실제로 이동
4. 배차 시뮬레이션 실행이 500 미발생
5. WebSocket 이 `wss` 로 연결되고 위치 갱신 수신
6. 재배포(`workflow_dispatch`) 후 1~5 재통과
7. 브라우저 콘솔에 CORS 오류 부재
8. `nmap <EIP>` 기준 개방 포트가 80·443 뿐(22·5432·6379·9092 폐쇄)
9. **첫 배포 직후 백업을 손으로 1회 돌리고**(`sudo BACKUP_BUCKET=<백업버킷> /opt/school-bus/infra/scripts/backup-db.sh` — 인자 없이 DB·사진 둘 다) S3 `db/`·`photos/` 에 적재되고 Prometheus 에서 `schoolbus_backup_db_last_success_timestamp_seconds` · `schoolbus_backup_photos_last_success_timestamp_seconds` 두 시리즈가 보이는지 확인한다(안 하면 첫 정시 백업까지 최대 1시간 `BackupDbStale` 이 울린다). 그리고 **복구 연습 1회**(§7 · 소요 시간을 §7.1 에 기록)
10. 일일 스냅샷 정책 동작(§2.4.1) · 경보 수신 확인(§11.3 — 테스트 알림 1건) · 첫 메인 관리자 외에 **두 번째 메인 관리자 생성**(§13.1)

**최초 배포는 10분 이상 걸릴 수 있다.** 이미지 5종을 처음 받고(백엔드 ~400MB + 인프라 ~700MB) backend 의 `start_period`(90s)와 Flyway 마이그레이션이 순차로 붙는다. 워크플로의 상태 판정 상한은 이를 감안한 20분(`MAX_WAIT_SECONDS=1200`) — 진행 중인데 실패로 오판해 운영자가 재실행하면 중복 배포가 큐에 쌓인다(`concurrency: cancel-in-progress: false`).

---

## 3. SSM 파라미터 목록

파라미터 경로 접두사는 `/school-bus/demo/` — `infra/scripts/deploy.sh` 의 `PARAM_PREFIX="/school-bus/demo"` 와 일치해야 한다. 아래 **필수 10개**는 `deploy.sh` 가 `get_param` 으로 반드시 조회하는 이름이다(하나라도 빠지거나 값이 비어 있으면 에러를 stderr 에 남기고 배포가 그 자리에서 실패 — 조용히 넘어가지 않는다. 실패한 배포는 기존 `/opt/school-bus/.env` 를 건드리지 않는다).

| 이름 | 타입 | 값/생성법 |
|---|---|---|
| `/school-bus/demo/ECR_REGISTRY` | String | `<계정ID>.dkr.ecr.ap-northeast-2.amazonaws.com` |
| `/school-bus/demo/DB_PASSWORD` | SecureString | `openssl rand -base64 24` |
| `/school-bus/demo/JWT_SECRET` | SecureString | `openssl rand -base64 48`(32바이트 이상 필수 — `jwt.secret` 이 서명 키로 직접 쓰임) |
| `/school-bus/demo/SEED_PASSWORD_HASH` | SecureString | §4 참조. `demo` 프로파일이 Flyway placeholder `seedPasswordHash` 로 주입 — 미주입 시 기동 실패가 정상(placeholder 미해결) |
| `/school-bus/demo/CORS_ALLOWED_ORIGINS` | String | `https://app.<도메인>` — **Vercel 에 연결한 커스텀 도메인 하나**(REST 전용. `SecurityConfig` 의 `app.cors.allowed-origins` 로 주입, `/api/**` 에만 적용). 정확한 `https` 출처만 — 와일드카드 · `http` · 경로 · `*.vercel.app` 은 `deploy.sh` 가 배포를 멈춘다(§12.2). 출처를 늘리려면 쉼표로 잇는다 |
| `/school-bus/demo/WS_ALLOWED_ORIGIN_PATTERNS` | String | 위와 같은 값 `https://app.<도메인>`(STOMP 전용. **`CORS_ALLOWED_ORIGINS` 와 별개** — WebSocket 핸드셰이크는 CORS 필터를 타지 않고 `WebSocketConfig` 의 `setAllowedOriginPatterns` 로 별도 검증하므로 이 값이 **유일한 방어선**이다). 같은 검사가 걸린다 |
| `/school-bus/demo/NAVER_DIRECTIONS_KEY_ID` | SecureString | NCP 콘솔에서 Directions API 발급. **키 미보유 시에도 반드시 등록** — 아래 참고 |
| `/school-bus/demo/NAVER_DIRECTIONS_KEY` | SecureString | NCP 콘솔에서 Directions API 발급. **키 미보유 시에도 반드시 등록** — 아래 참고 |
| `/school-bus/demo/SPRING_PROFILES_ACTIVE` | String | **`prod` 또는 `demo`** — 다른 값이면 배포 중단. 기본값이 없다: 예전에는 compose 기본값 `demo` 라 이 값이 빠진 배포가 가짜 시드 + 가짜 버스(`DemoRunSimulator`)로 조용히 떴다. `prod` = 실 운영(계정 0개 · 첫 관리자는 아래 선택 항목), `demo` = 데모 시드 |
| `/school-bus/demo/GRAFANA_ADMIN_PASSWORD` | SecureString | `openssl rand -base64 24`. 운영 Grafana 초기 관리자(`admin`) 비밀번호 — §11. 기본 비밀번호로 뜨지 않게 필수 |

**`prod` 일 때만 필수인 3개 — FCM 푸시(`Ruling 331`)** — `FCM_PROJECT_ID`(String) · `FCM_CLIENT_EMAIL`(String) · `FCM_PRIVATE_KEY`(SecureString, 서비스 계정 JSON 의 `private_key` — 줄바꿈이 `\n` 문자여도 받는다). Firebase 프로젝트 생성·서비스 계정 키 발급은 사용자 작업. `SPRING_PROFILES_ACTIVE` 가 `prod` 인데 셋 중 하나라도 없으면 `deploy.sh` 가 1단계에서 멈춘다(앱을 띄워 기동 실패를 보는 것보다 컨테이너를 건드리기 전에 막는 편이 낫다). `demo` 는 읽지 않는다(로그 전용 발송).

**선택 항목 — 없으면 컨테이너가 `application.yml` 기본값으로 뜬다**(`deploy.sh` 는 `ParameterNotFound` 만 "없음"으로 보고, 권한 오류·네트워크 오류는 선택 항목이어도 배포를 멈춘다 — 못 읽은 값을 없는 값으로 취급하면 운영이 조용히 기본값으로 뜬다). 필수 계약에 섞지 않으려고 이름을 따로 모은다.

| 이름 | 타입 | 값/생성법 |
|---|---|---|
| `NAVER_SEARCH_CLIENT_ID` · `NAVER_SEARCH_CLIENT_SECRET` | SecureString | NCP 콘솔 → NAVER API HUB → Application(지도 키와 **다른** 애플리케이션). 없으면 장소 이름 검색이 401 로 실패해 주소 후보만 나간다 |
| `NAVER_DIRECTIONS_PATH` · `NAVER_DIRECTIONS_MAX_POINTS` | String | **짝** — 하나만 있으면 배포 중단(`Ruling 361`). Directions 15 일일 한도(3,000)가 바닥났을 때 Directions 5 로 전환: `/map-direction/v1/driving` + `7`. 되돌리려면 두 파라미터를 함께 삭제하고 재배포 |
| `BOOTSTRAP_ADMIN_LOGIN_ID` · `BOOTSTRAP_ADMIN_PASSWORD_HASH` | String · SecureString | **짝** — 첫 메인 관리자(`prod` 첫 배포용, 아래). 해시는 §4 절차로 만든 bcrypt 해시이며 평문이면 배포가 멈춘다 |
| `ALERT_TELEGRAM_BOT_TOKEN` · `ALERT_TELEGRAM_CHAT_ID` | SecureString · String | **짝** — 경보를 텔레그램으로 받는다(§11.3). 토큰은 `<숫자>:<문자열>`, 채팅 ID 는 숫자(그룹은 음수). 없으면 텔레그램으로 안 보낸다 |
| `ALERT_EMAIL_TO` · `ALERT_SMTP_HOST` · `ALERT_SMTP_USER` · `ALERT_SMTP_PASSWORD` | String · String · String · SecureString | **넷이 짝** — 경보 이메일 예비 채널(§11.3). `ALERT_SMTP_HOST` 는 `호스트:포트`(예 `smtp.gmail.com:587`), 발신 주소는 SMTP 계정과 같다. 없으면 이메일로 안 보낸다. 반쪽(일부만 등록)이면 `deploy.sh` 가 배포를 멈춘다 |

`PHOTO_STORAGE_ROOT` 는 SSM 으로 열지 않는다 — compose 가 named volume 마운트 경로(`/app/var/photos`)와 같은 값으로 고정한다. 어긋나면 사진이 컨테이너 안에만 쌓여 재배포마다 사라진다.

**첫 메인 관리자(`prod`).** `prod` 는 계정이 0개이고 가입 API 로는 메인 관리자를 만들 수 없다. 앱이 기동할 때 `FirstSystemAdminBootstrap` 이 **메인 관리자가 하나도 없을 때만** 위 두 값으로 활성 계정 1개를 만든다. 이미 있으면 아무것도 하지 않는다(값은 무시 — 첫 배포 뒤 남아 있어도 된다). 값이 잘못됐으면 기동이 멈추고 로그에 이유가 남는다(평문 비밀번호 · 한쪽만 있음 · 이미 쓰는 아이디). 로그에는 비밀번호·해시를 남기지 않는다. 절차: ① §4 로 해시 생성 ② 두 파라미터 등록 ③ 배포 ④ 로그인 ⑤ **비밀번호 변경 후 두 파라미터 삭제**(값이 SSM 에 남는 시간을 줄인다). 로그인이 안 되면 `docker compose logs backend | grep BOOTSTRAP` 로 거부 사유를 본다.

**⚠️ NCP 키가 없어도 위 두 항목은 등록해야 한다.** `deploy.sh` 의 `get_param` 은 필수 10개 전부를 필수로 보고, 파라미터가 없거나 값이 비면 **1단계에서 배포를 중단**한다. 키가 아직 없으면 두 항목에 `unused` 같은 임의 문자열을 넣어 등록한다 — 그러면 도로 경로 호출이 실패해 노선이 직선거리 근사(`fallback_used=true`)로 나오고 미리보기·경유 지점 지정은 `503 MAP_ROUTE_UNAVAILABLE` 이 된다. **대체 지도 공급자는 없다**(`Ruling 551`) — 예전에 `ROUTING_PROVIDER=osrm` 으로 우회한다고 적었으나 OSRM 구현체가 없고 `application.yml` 도 그 이름을 읽지 않아 안내가 효과가 없었다(변수 삭제). 지도 공급자는 코드가 `app.routing.map.provider` 로 고르며 값은 `naver`(기본) · `stub`(시험용 가짜 경로 — 운영에서 쓰지 않는다) 둘뿐이라 SSM 으로 바꾸는 항목을 두지 않는다. 필수 계약을 단순하게 유지하려는 의도적 설계다 — 그래서 새로 늘린 값 중 없어도 되는 것은 위 "선택 항목" 표에 따로 모으고, 필수 표에는 섞지 않았다(섞으면 어떤 값이 비어도 되는지가 스크립트·문서·compose 세 곳에서 갈린다). `SEED_PASSWORD_HASH` 는 `prod` 에서 쓰이지 않지만 같은 이유로 계속 필수다(임의의 bcrypt 해시를 등록). 이미 SSM 에 등록된 `ROUTING_PROVIDER` 파라미터는 아무도 읽지 않으므로 지워도 된다.

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
- 로컬 재현: `scripts/verify.sh`(전부) · `scripts/verify.sh web flutter`(골라서). 백엔드는 `backend/scripts/test.sh` 가 전용 DB 를 만들고 지움 — `verify.sh` 는 웹·백엔드를 CI 와 같이 **`TZ=UTC`** 로 돌리고 백엔드에는 `-PciQuiet` 도 준다
- **러너는 UTC · Linux 이고 개발 기계는 한국 시간대 · macOS 다 — "로컬은 통과 · CI 만 실패" 의 원인이 된 세 가지**(`Ruling 720`~`723`, 2026-10-01 첫 push 에서 8건). ①**시간대** — 시험이 기대값을 기기 시간대로 계산하면(`toLocaleTimeString` 에 `timeZone` 없음) UTC 에서 화면(서울 고정)과 어긋난다. 기대값은 서울 시각 문자열로 박는다 · 오프셋 없는 날짜시각을 `new Date()` 에 그대로 넘기지 않는다(기기 시간대로 읽힌다 — 웹 `formatClockTime` 이 이 입력을 서울로 읽게 고쳤다). ②**시각 정밀도** — Linux 의 `OffsetDateTime.now()` 는 나노초 · macOS 는 마이크로초 · `timestamptz` 는 마이크로초라, DB 에 저장했다 읽은 값을 `isEqualTo` 로 비교하는 시험은 **Linux 에서만 실패**한다. 시험에서 그런 시각을 만들 때 `.truncatedTo(ChronoUnit.MICROS)` 를 붙인다(macOS 에서는 이 결함이 안 드러난다). ③**로그 수준** — `-PciQuiet` 이 루트 로그를 WARN 으로 낮추므로 INFO 로그를 읽는 시험은 **자기 로거의 수준을 직접 켜고 복원**한다(앞선 컨텍스트가 같은 JVM 에 남긴 수준에도 달려 단독 실행은 통과 · 전체 실행은 실패하는 형태가 된다)
- **로그 정책 — 저장소가 공개라 Actions 로그도 공개다.** 비밀값 없이 돈다(네이버 키 불필요 — `build.gradle` 이 지오코딩·경로·장소검색을 stub 으로 고정). 백엔드는 `-PciQuiet` 으로 로그 수준을 WARN 으로 낮추고 결과 XML 에서 stdout·stderr 를 뺀다 — 실패 때 남는 것은 시험 이름·실패 요약뿐. `deploy-backend.yml` 도 실패 시 backend 컨테이너 로그 120줄을 찍지 않고 `deploy.sh` 가 쓴 실패 사유 줄만 남긴다(로그는 EC2 에서 확인)
- 결과 XML 은 artifact `backend-test-results`(7일)로 남는다
- 실 네이버 API 시험은 CI 에서 돌지 않는다 — 자격증명이 없고 Directions 일일 한도가 있다. 실행법은 `CLAUDE.md` "Build & run"
- 의존성·이미지·액션 갱신은 `.github/dependabot.yml`(주 1회 · 생태계별 묶음)이 PR 로 올린다. Docker 이미지 태그는 부 버전까지 고정

동시 배포 처리: 백엔드는 `concurrency: cancel-in-progress: false` — 겹치면 취소 대신 줄을 세운다(옛 이미지가 새 이미지를 덮어쓰는 사고 방지).

`.htpasswd` 는 `infra/**` 배포에 영향받지 않는다 — `deploy-backend.yml` 의 두 `s3 sync` 가 `--exclude "proxy/.htpasswd"` 로 이 파일을 동기화·삭제 대상에서 제외한다(§2.12). 배포마다 재업로드는 불필요.


### 5.2 종료 대기 — `stop_grace_period` 와 스프링 정상 종료의 관계 (`Ruling 643`)

배포 때 `docker compose up -d` 가 옛 backend 를 멈추는 순서는 **종료 신호(SIGTERM) → 대기 → 강제 종료(SIGKILL)** 다. 대기 시간 두 개가 겹친다.

| 값 | 위치 | 크기 |
|---|---|:-:|
| 스프링 정상 종료 대기 — `server.shutdown=graceful` · `spring.lifecycle.timeout-per-shutdown-phase` | **설정 파일에 명시가 없고 Boot 4.1 기본값이다**(4.1.0·4.1.1 설정 메타데이터에서 확인) | 30초 |
| Docker 가 SIGKILL 을 보내기까지 — `stop_grace_period` | `docker-compose.prod.yml`·`docker-compose.staging.yml` backend | 지정 전 기본 10초 → **35초** |

- 스프링은 신호를 받으면 새 요청을 막고 진행 중인 요청을 최대 30초 기다린다. Docker 기본 10초면 그 사이 SIGKILL 이 와 진행 중이던 요청이 오류로 끊긴다. 데이터는 조건부 UPDATE·단일 트랜잭션·아웃박스 복구로 손상되지 않아 피해는 그 요청의 클라이언트 오류 1건 수준이지만, 막을 수 있는 오류다
- **Docker 값은 스프링 값보다 길어야 한다.** 35초 = 30초 + 5초(연결 풀·메시지 브로커를 닫는 시간). 스프링 값을 올리면(`timeout-per-shutdown-phase: 60s` 등) compose 의 값도 같이 올린다 — `OpsSettingsGuardTest` 가 `application.yml` 에 명시된 값을 읽어 어긋나면 실패한다. 명시가 없는 지금은 Boot 기본값 30초를 상수로 가정하니 Boot 를 올릴 때 설정 메타데이터를 다시 확인한다
- 35초는 상한이다. 한가한 시각의 배포는 진행 중 요청이 없어 즉시 끝난다. 배포 게이트(`deploy-gate.sh`)가 운행 중 회차를 막는 것과는 별개의 장치다
- 스테이징은 같은 값이다(`docker compose restart backend` 로 하는 초기화도 이 시간을 쓴다 — `STAGING §8`)

### 5.3 nginx 설정 반영 — 검사 후 proxy 다시 시작 (`Ruling 615` · `648`)

`deploy.sh` 의 **3-1 단계**(`up -d` 뒤 · 스모크 테스트 앞)가 **매 배포마다** 두 가지를 한다 — ① `docker compose run --rm --no-deps -T proxy nginx -t` 로 새 설정 검사 ② 통과하면 `restart proxy`. 설정을 바꾼 배포도 아닌 배포도 같은 길을 지난다(바뀐 줄을 가려내는 로직이 없어 틀릴 곳이 없다).

| 질문 | 답 |
|---|---|
| **왜 다시 시작해야 하나** | proxy 는 `nginx.prod.conf` **파일 하나**를 바인드 마운트한다. `s3 sync` 는 임시 파일에 받아 이름을 바꿔 교체해(새 파일이 생긴 것) 마운트가 옛 파일을 계속 가리킨다 — 일회용 컨테이너 안의 Linux 마운트로 확인: 교체 뒤에도 옛 내용, 다시 마운트하면 새 내용. `nginx -s reload` 는 옛 설정을 다시 읽을 뿐이라 소용없다. `up -d` 는 설정 파일 내용을 보지 않고, backend 가 재생성돼도 proxy 를 다시 만들지 않는다(compose 5.5.1 실측) |
| **왜 폴더 마운트 + reload 가 아닌가** | `infra/proxy/` 에 개발용 `nginx.conf`·`nginx.staging.conf` 가 같이 있어 그 폴더를 `conf.d/` 로 마운트하면 `server`·`limit_req_zone` 이 겹친다. 운영 설정을 전용 폴더로 옮기고 이름을 바꿔야 하는데, 이름이 코드·시험·문서 20여 곳에 있다. 얻는 것은 1~2초 무끊김뿐이라 사용자가 허용한 재시작을 골랐다 |
| **끊김** | proxy 재시작 **1~2초**(80·443 연결 거절, WebSocket 재연결). 같은 배포에서 backend 가 이미 수십 초 내려가 있어(§10) 체감 추가분은 작다. 재시작은 `upstream backend` 이름도 새로 풀게 한다 |
| **검사가 실제와 같은 조건인 이유** | `run` 은 새 컨테이너라 마운트를 새로 해 **교체된 새 파일**을 보고, 같은 compose 네트워크·볼륨이라 `backend` 이름 해석 · 인증서(`certbot-conf`) · `.htpasswd` 마운트가 실제 proxy 와 같다. `--no-deps` 라 다른 서비스는 건드리지 않는다. 일회용 환경에서 확인: 정상 설정 통과 · 문법 오류·인증서 경로 없음·backend 이름 해석 실패는 종료코드 1 |
| **검사가 실패하면** | **proxy 를 다시 시작하지 않고 배포를 실패로 끝낸다**(`exit 1`, 로그에 `nginx: [emerg] … in /etc/nginx/conf.d/default.conf:<줄>`). 옛 설정으로 proxy 는 계속 응답한다. Prometheus·Alertmanager 반영이 "경고만" 인 것과 다르다 — 잘못된 설정으로 다시 시작하면 proxy 가 못 떠 API 전체가 멈춘다. 고쳐서 다시 배포한다(§8) |
| **검사 위치가 `up -d` 뒤인 이유** | backend 가 떠 있어야 `upstream backend:8080` 이름이 풀린다 — 먼저 하면 정상 설정도 `host not found in upstream` 으로 실패한다(가드 시험이 순서를 고정). 한계: 같은 배포가 compose 파일(이미지·마운트·명령어)도 바꿔 `up -d` 가 proxy 를 새 설정으로 **재생성**하면, 잘못된 설정은 그 시점에 이미 proxy 를 못 뜨게 한 뒤다. 이때도 3-1 이 같은 파일로 실패해 배포를 "성공" 으로 끝내지 않는다 — 복구는 §6 롤백 또는 설정을 고쳐 다시 배포 |

- 첫 배포 뒤 확인: 배포 로그의 `== 3-1.` 아래에 `configuration file /etc/nginx/nginx.conf test is successful` 가 있어야 한다. EC2 의 compose 는 v2.29.7 이고 위 동작(`run --no-deps`·`restart`)은 v2 공통이지만, "backend 재생성이 proxy 를 다시 만들지 않는다" 는 로컬 v5.5.1 에서만 확인했다
- 검사용 컨테이너도 `x-logging` 을 받아 배포마다 CloudWatch 로그 스트림이 하나 늘고 2줄이 남는다
- `.htpasswd` 는 `s3 sync` 가 건드리지 않는 EC2 직접 파일이라(§2.12) 이 단계의 대상이 아니다. 그 파일을 새로 만들었을 때의 재기동(§2.12 3번)은 그대로 필요하다

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

## 7. 백업 · 복구 — 목표 유실 1시간(RPO) · 복구 1시간(RTO)

`Ruling 480 ①`(사용자 확정)·`Ruling 500`. 이 절과 `TECH_DECISIONS §12.3` 은 같은 값이다. 옛 정책 둘(연속 복구 방식 대 일 1회 덤프)이 서로 어긋나 있던 것을 이 값으로 통일했다.

| 데이터 | 목표 유실(RPO) | 수단 |
|---|---|---|
| DB — 승하차 이력 · 감사 로그 포함 | **1시간** | 매시 정각 `pg_dump` → S3 `db/`(7일 보관) |
| 학생 사진 | 하루 | 매일 03:10 전체 묶음 → S3 `photos/`(7일) · 사진은 다시 올릴 수 있어 DB 와 같은 값으로 묶지 않는다 |
| 전부(DB · 사진 · 인증서 · 지표) | 하루 | 매일 EBS 스냅샷 7개(§2.4.1) — 덤프가 손상됐을 때의 2차 방어선 |

**복구 시간 목표(RTO) 1시간** — 인스턴스 사망을 안 시점부터 로그인이 되는 시점까지(§7.3).

**백업이 멈추면 경보가 울린다**(`BackupDbStale` 2시간 · `BackupPhotosStale` 26시간 — §11.2). 크론 실패는 `/var/log/schoolbus-backup.log` 에만 남아 아무도 읽지 않으므로, `backup-db.sh` 가 S3 업로드를 마친 뒤에만 쓰는 성공 시각 지표(`/var/lib/node_exporter/textfile/schoolbus_backup_*.prom`)가 유일한 감지 수단이다.

### 7.0 DB 복구 리허설 — 첫 배포 직후 반드시 1회

**반드시 1회 수행 후 결과를 §7.1 에 기록한다**(소요 시간 포함). 이 절차서 작성 시점에는 실 AWS 계정·백업 데이터가 없어 **미수행** — 최초 배포 완료 후 담당자가 직접 1회 실행하고 결과를 남긴다. 연습 항목: ① 아래 스크래치 DB 복원 ② 데이터 디스크 스냅샷에서 볼륨을 만들 수 있는지(`aws ec2 create-volume --snapshot-id <스냅샷> --availability-zone <AZ>` 후 바로 삭제) ③ 걸린 시간 기록. 인스턴스 전체 재구축(§7.3)은 RTO 를 실측해야 할 때 한 번 한다.

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

| 수행일 | 수행자 | 대상 백업 파일 | 결과 | 걸린 시간(목표 1시간) | 비고 |
|---|---|---|---|---|---|
| (미기록 — 최초 배포 후 1회 수행 필요) | | | | | |

### 7.2 학생 사진 복원

`backup-db.sh` 가 DB 덤프와 함께 사진 묶음(`photos/<날짜시각>.tar.gz`)을 올린다(매번 전체). 복원은 backend 컨테이너가 떠 있는 상태에서 한 줄이다 — 묶음 안 경로가 `photos/...` 라 `/app/var` 에 풀면 named volume(`photo-data`)의 제자리로 들어간다.

```bash
sudo aws s3 cp s3://<백업버킷>/photos/<날짜시각>.tar.gz - \
  | sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env \
    exec -T backend tar xzf - -C /app/var
```

같은 이름의 파일은 덮어쓰고 없는 파일은 되살린다(지우지는 않는다). 사진이 수 GB 를 넘으면 매번 전체를 올리는 방식을 `aws s3 sync` 로 바꾼다(`backup-db.sh` 주석).

### 7.3 인스턴스 사망 복구 — 목표 1시간

인스턴스가 사라졌거나(하드웨어 장애 · 실수 종료) 접속이 안 되고 되살릴 수 없을 때. `/healthz` 외부 감시(§11.4)가 알린 시점이 시작이다. **먼저 데이터 디스크가 살아 있는지** 본다(`aws ec2 describe-volumes --filters Name=tag:Name,Values=school-bus-data --query 'Volumes[].[VolumeId,State,AvailabilityZone]'`).

**경로 A — 데이터 디스크가 살아 있다**(인스턴스만 사라진 경우 — 이 디스크는 종료 때 삭제되지 않게 만들었다). DB 복원이 필요 없다.

1. 새 EC2 를 §2.4 와 같은 AMI·보안 그룹·인스턴스 프로파일로 만든다. **`/dev/sdf` 매핑은 빼고** 만든다. 새 인스턴스와 **같은 AZ** 에서 옛 데이터 디스크를 붙인다: `aws ec2 attach-volume --volume-id <옛 데이터 볼륨> --instance-id <새 인스턴스> --device /dev/sdf` (다른 AZ 면 경로 C)
2. 고정 IP 를 옮긴다: `aws ec2 associate-address --instance-id <새 인스턴스> --allocation-id <ALLOC_ID> --allow-reassociation`
3. §2.8 대로 `infra/` 를 S3 에서 내려받고 `bootstrap-ec2.sh` 를 실행한다 — 이미 파일시스템이 있는 디스크라 **포맷하지 않고 그대로 마운트**한다(인증서·DB·사진·지표가 그대로 보인다)
4. `.htpasswd` 를 다시 만든다(§2.12 — 루트 디스크에 있어 사라진다)
5. 배포한다(`deploy-backend.yml` `workflow_dispatch` 또는 EC2 에서 `deploy.sh <SHA>`) → §2.14 의 1·2번으로 확인

**경로 B — 데이터 디스크도 사라졌다**(볼륨 삭제 · 손상). 1시간 안의 데이터는 최신 덤프에서 되살린다.

1. 경로 A 의 1~2 와 같되 `/dev/sdf` 매핑을 **넣어** 새 데이터 디스크를 만든다(§2.4 명령 그대로 — 부트스트랩이 포맷한다). 3 은 같다
2. 인증서를 다시 발급한다(§2.10 — `api` A 레코드는 EIP 라 그대로이다) · `.htpasswd`(§2.12) · 배포(backend 가 빈 DB 에 스키마를 만든다)
3. 복원 — backend 를 멈추고 스키마를 비운 뒤 최신 덤프를 **한 트랜잭션**으로 넣고(`-1` · 오류가 나면 전부 취소) backend 를 다시 켠다:

```bash
cd /opt/school-bus
C="sudo docker compose -f docker-compose.prod.yml --env-file .env"
$C stop backend
$C exec -T postgres psql -U schoolbus schoolbus -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
LATEST=$(sudo aws s3 ls s3://<백업버킷>/db/ | sort | tail -1 | awk '{print $4}')
sudo aws s3 cp "s3://<백업버킷>/db/$LATEST" - | gunzip | $C exec -T postgres psql -U schoolbus schoolbus -v ON_ERROR_STOP=1 -1
$C start backend
```

4. 사진은 §7.2 로 최신 묶음(`photos/`)을 푼다(하루 전까지)
5. §2.14 의 1·2번으로 확인. 복원한 덤프의 시각이 곧 실제 유실 구간이므로 `$LATEST` 를 기록한다

**경로 C — 다른 AZ 로 옮기거나 덤프가 손상됐다**: 스냅샷으로 새 볼륨을 만든다 — `aws ec2 create-volume --snapshot-id <스냅샷> --availability-zone <새 인스턴스 AZ> --volume-type gp3` 뒤 경로 A 의 1단계부터. 최대 하루 전 상태다.

**소요 시간 추정(첫 연습에서 실측해 §7.1 에 적는다)**: 인스턴스 생성·부팅 5분 · 부트스트랩(`dnf update` 포함) 10분 · 이미지 수신·배포 10~15분 · 복원 5분 · 확인 5분 = 약 40분. 실측 전에는 추정일 뿐이다.

---

## 8. 장애 대응

증상별 확인 명령 · 조치 · 기록 대상. `TECH_DECISIONS §14.2`(실패 모드 7행)·`§14.3`(수동 개입 4행)과 이 절 원래 7행을 한 표로 통합.

| 증상 | 확인 명령 | 조치 | 남길 것 |
|---|---|---|---|
| 로그인 401 반복 | `.env` 의 `SEED_PASSWORD_HASH` 값과 실제 비밀번호 해시 일치 여부 확인 | 해시 재발급 후 SSM 파라미터 갱신, `deploy.sh` 재실행 | 재발급 사유·시각 |
| 브라우저 CORS 오류 | `CORS_ALLOWED_ORIGINS` 값에 스킴 포함 정확한 출처 존재 여부 확인 | 누락 출처 추가 후 SSM 갱신·재배포 | 추가한 출처 값 |
| WebSocket 만 연결 실패(REST 는 정상) | `WS_ALLOWED_ORIGIN_PATTERNS` 값과 nginx `/ws/` 블록 Upgrade 헤더 확인 | 패턴 또는 nginx 설정 수정 후 `proxy` 컨테이너 재기동 | 수정한 패턴·설정 값 |
| 배차·시뮬레이션 `503 MAP_ROUTE_UNAVAILABLE` · 확정 노선에 `fallback_used=true` 다수 | NCP 키 유효성(`.env` 의 `NAVER_DIRECTIONS_KEY_ID`·`NAVER_DIRECTIONS_KEY`) · NCP 콘솔의 Directions 일일 한도 | 키 재발급 후 SSM 갱신·재배포. 대체 공급자는 없다(`Ruling 551`) — 복구될 때까지 직선거리 근사로 계속 확정된다 | 재발급 사유·시각 |
| 컨테이너 반복 종료 | `free -h` 로 메모리, `docker stats`, 스왑 활성 여부 확인. backend 면 `docker inspect -f '{{.RestartCount}}' <컨테이너>` 와 `docker compose logs backend \| grep OutOfMemoryError`(§11.6 — JVM 이 OOM 이면 스스로 끝나 재시작된다) | 스왑 추가 또는 인스턴스 사양 상향. OOM 반복이면 힙 사용이 늘어난 원인(최근 변경 · 폴링 부하)을 먼저 본다 — 힙 덤프는 남지 않는다(§11.6) | 종료 시점·메모리 수치 |
| 인증서 만료 | `docker compose logs certbot`, 80 포트 개방 여부 확인 | certbot 갱신 재시도, 방화벽 규칙 수정 | 갱신 결과 |
| Swagger UI 401 · 기동 실패 | §2.12 절차 확인 | EC2 에서 `.htpasswd` 재생성 후 `proxy` 컨테이너 재기동 | 재생성 시각 |
| 배포가 `== 3-1.` 단계에서 멈춤(`nginx -t` 실패 · `Ruling 648`) | 배포 로그의 `nginx: [emerg] … default.conf:<줄>` — 줄 번호는 `infra/proxy/nginx.prod.conf` 의 줄이다. `host not found in upstream "backend:8080"` 이면 설정이 아니라 backend 가 안 떠 있는 것 — `docker compose ps backend` | 설정 오류는 그 줄을 고쳐 커밋하고 다시 배포한다(§5.3). proxy 는 다시 시작하지 않아 **옛 설정으로 응답 중**이라 API 는 살아 있다 | 실패 로그 줄 · 고친 줄 |
| 지도 API 장애(노선 계산 ③단계 영향) | `resilience4j_circuitbreaker_state{name="geocoding"\|"mapRoute"}` 값 확인(관측 목표 8) | 자동 폴백 — 직선거리 근사로 배차 유지, 화면에 폴백 사실 표시, 배치는 계속 진행. 서킷 닫히면 다음 회차부터 정상, 이미 배포된 노선은 재계산 제외 | 폴백 지속 시간 |
| Redis 장애(최신 좌표·캐시 영향) | `docker compose logs redis`, `redis-cli ping`, 지표 `schoolbus.position.fallback` 증가 | 자동 대체 — 위치 조회 3종(학부모 버스 위치 · 관계자 관제 · 메인 관리자 관제)과 비상 신고 위치는 DB 이력 최신 행으로 조회(현재 정차지 이름은 비어 나감). 근접·출발 판정은 그 틱을 건너뛰고 다음 틱에 다시 본다. 캐시 미스로 계산 반복 | 장애 지속 시간 |
| DB 장애(전면 영향) | `docker compose ps postgres`, 헬스체크 UP 여부 확인 | 매니저 앱은 오프라인 큐로 승하차만 지속, 나머지 제품은 조회 불가. 복구 후 큐 동기화(멱등, `client_key` UNIQUE) | 장애 시작·복구 시각 |
| 앱 인스턴스 재시작(WS 끊김·배치 중단) | `docker compose ps backend`, 재시작 로그 확인 | 자동 복구 — 클라이언트 자동 재접속, 배치는 폴링이라 놓친 회차를 다음 틱에 자동 회수. 별도 절차 부재 | 재시작 원인 |
| 배치 밀림(확정 지연) | `schoolbus_run_confirmation_lag_seconds` 값 확인(관측 목표 8 — 값이 계속 늘면 워커 수·인스턴스 증설 신호) | 도래분은 다음 틱으로 자동 이월. 지속되면 워커 수 증설, 그다음 인스턴스(§3) | 지연 수치 추이 |
| 확정 실패한 회차(그 회차만 노선 부재) | Prometheus `RunUnconfirmed` 경보(§11 · 확정 시각을 5분 넘긴 idle 회차 > 0 이 1분 유지) · `schoolbus_run_confirmation_retry_failures_total` 값 확인(관측 목표 8) | `idle` 복귀 후 자동 재시도. 이 경보가 **1차 방어**다 — 경보 수신 값(§11.3)이 SSM 에 있으면 텔레그램·이메일로 오고, 없으면 Prometheus `/alerts` 를 직접 봐야 하며 외부 감시(`/healthz`)는 서버 사망만 알린다. 아래 행으로 이어짐 | 실패 회차 id·연속 실패 횟수 |
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
| 배포마다 proxy 1~2초 추가 끊김 | 파일 하나를 마운트한 nginx 는 reload 로 새 설정을 못 읽어 다시 시작한다(`Ruling 615` · `648`, §5.3). 폴더 마운트로 바꾸면 없앨 수 있으나 설정 파일 이름이 코드·시험·문서 20여 곳에 있다 |
| 인스턴스 사망 시 복구가 수동(목표 1시간) | 데모 성격. Auto Scaling Group 미구성. 절차는 §7.3 |
| DB 최대 1시간 · 사진 최대 하루 유실 | 매시 덤프 + 매일 스냅샷(RPO 1시간, `Ruling 480 ①`). WAL 아카이빙(PITR)은 채택하지 않았다 — 이 규모에서 설정·복구 복잡도가 이득보다 크다 |
| APM 부재 · 서버 통째 사망은 외부 감시로만 감지 | 경보 수신(텔레그램·이메일, §11.3)은 있으나 Prometheus·Alertmanager 가 EC2 안에 있어 서버가 죽으면 같이 죽는다 — `/healthz` 외부 감시(§11.4)를 걸어야 알 수 있다 |

---

## 11. 운영 관측 (2026-10-01 · R46 ops)

운영 compose 에 `prometheus` · `alertmanager` · `node-exporter` · `grafana` 가 함께 뜬다. 설정·경보 규칙·대시보드 5종은 개발용 `docker-compose.app.yml` 과 같은 `infra/observability/` 파일이다(운영 전용은 `prometheus/prometheus.prod.yml` 과, 배포 때 만들어지는 Alertmanager 설정 — §11.3).

| 항목 | 값 |
|---|---|
| 지표 보존 | 15일(`prometheus-data` 볼륨 — 컨테이너를 다시 만들어도 남는다) |
| 메모리 한도 | prometheus 512m · grafana 256m · alertmanager 96m · node-exporter 64m (합 928MB) |
| 외부 노출 | **없음** — Prometheus(`127.0.0.1:9090`)·Grafana(`127.0.0.1:3001`)는 호스트 loopback 에만 바인딩하고, exporter·Alertmanager 는 컨테이너 네트워크 안에서만 닿는다. 보안 그룹은 80·443 뿐이다 |
| 운영에 없는 것 | postgres-exporter · redis-exporter — 그래서 대시보드 `3. 데이터 계층` 의 postgres·redis 패널은 비어 있다 |

⚠ **메모리.** 한도의 합 928MB 는 backend 한도(3GB)와 합치면 `t3.medium`(4GB)을 넘는다. 한도는 상한이지 예약이 아니고 실사용은 대략 300MB 로 추정하지만(측정 전) 배포 순간 신·구 backend 가 함께 살아 있는 시점에는 스왑 2GB(§2.8)에 기댄다. 관측을 켠 채 안정적으로 운영하려면 권장 사양(4 vCPU · 8GB — 2026-09-09 부하 한계 측정 보고서 13절 · `Ruling 351`)으로 올린다.

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

규칙은 `infra/observability/prometheus/alerts.yml` 이고, 조건식·`for` 는 `alerts.test.yml`(promtool 단위 시험)이 가짜 시계열로 검사한다. 규칙을 고친 배포는 `deploy.sh` 가 Prometheus 를 **다시 시작**해 반영한다 — 규칙 파일 하나를 바인드 마운트한 컨테이너는 파일이 교체돼도 옛 파일을 계속 보고, 다시 시작하지 않으면 옛 규칙으로 평가한다(2026-10-01 로컬에서 실측. 지표는 볼륨에 남는다). `TECH_DECISIONS §13.4` 표와 행 단위로 대응한다.

| 경보 | 조건 | 등급 | `TECH_DECISIONS §13.4` 행 |
|---|---|---|---|
| `RunUnconfirmed` | `schoolbus_run_unconfirmed > 0` 이 1분 유지 | 즉시(critical) | 1행 |
| `NoShowEscalationFailing` · `NoShowEscalationStalled` | 미승차 에스컬레이션 실패 · 180초 초과 미성공 | 즉시(critical) | 2행 |
| `RunPositionLost` | `schoolbus_run_position_lost > 0` 이 1분 유지 | 경고 | 3행 |
| `StaleMovingRun` | `schoolbus_run_moving_stale > 0` 이 30분 유지 — 운행일이 어제보다 이른데 끝나지 않은(`moving`) 회차 | 경고 | (표 밖 — 근접 판정 · 위치 유실 · 노선 잠금이 그 회차를 더는 집지 않아(`Ruling 701`) 이 경보가 유일한 신호다. 같은 경보는 4시간마다 다시 알린다) |
| `PushDeliveryFailing` | 최근 10분 안에 `schoolbus_notification_push_failures_total` 증가 | 경고 | 5행 |
| `HostDiskAlmostFull` | 루트 디스크 사용률 80% 초과가 10분 유지 | 경고 | (표 밖 — 디스크가 차면 postgres 쓰기가 실패해 전면 정지) |
| `BackupDbStale` | DB 백업 성공 시각이 2시간 넘게 갱신되지 않거나 지표가 아예 없음(5분 유지) | 즉시(critical) | (표 밖 — 매시 백업 중 두 번 연속 실패하면 RPO 1시간을 못 지킨다, `Ruling 500`) |
| `BackupPhotosStale` | 사진 백업 성공 시각이 26시간 넘게 갱신되지 않거나 지표가 없음 | 경고 | (표 밖) |
| `BackendDown` | `up{job="backend"} == 0` 이거나 `up` 시계열이 없음(스크레이프 대상이 목록에서 사라짐)이 1분 유지 | 즉시(critical) | (표 밖 — 백엔드가 죽으면 위 백엔드 지표 경보가 값이 없어 **전부 조용해진다**) |
| `Http5xxRatioHigh` | 최근 5분 요청의 5% 초과가 5xx(actuator 제외 · 분당 6건 이상일 때만 판정)인 상태가 2분 유지 | 즉시(critical) | (표 밖) |
| `HikariPoolWaiting` | `hikaricp_connections_pending > 0` 이 1분 유지 | 경고 | (표 밖 — 풀 20개가 마르면 3초 뒤 500) |
| `CircuitBreakerNotClosed` | 지도 API 서킷(`geocoding` · `mapRoute` · `placeSearch`)이 열림·반열림으로 2분 넘게 닫히지 않음 | 경고 | 4행 |
| `SchedulerStalled` | 확정·알림 재전송 스케줄러가 90초, 근접 판정이 30초(각 주기의 3배) 넘게 마지막 성공이 없는 상태가 1분 유지 | 즉시(critical) | (표 밖 — 확정이 멈추면 `RunUnconfirmed` 보다 먼저 안다) |
| `RetentionCleanupStalled` | 보존 정리(매일 00:15)가 3일 넘게 성공하지 않음 | 경고 | (표 밖) |
| `RunPositionPartitionStalled` | 위치 이력 파티션 미리 만들기(매일 00:10 · 7일 앞까지, `Ruling 670`)가 3일 넘게 성공하지 않음 | 경고 | (표 밖 — 미리 만든 파티션이 4일치 남아 있는 동안 알려 기본 파티션에 행이 쌓이기 전에 고친다) |
| `StompSessionsNearCap` | `schoolbus_stomp_sessions{job="backend"} > 3000` 이 5분 유지(동시 연결 상한 4,000 의 75% — §11.7) | 경고 | (표 밖 — 상한에 닿으면 새 연결이 거절되는데 서버는 그 사실을 기록하지 않는다, `Ruling 691`) |

백업 2종은 `backup-db.sh` 가 **S3 업로드를 마친 뒤에만** 쓰는 성공 시각을 node-exporter 가 읽어 낸다(§7) — 덤프가 비었거나 업로드가 실패하면 시각이 갱신되지 않아 경보가 울린다. 6행(배치 지연 p95)만 아직 규칙이 없다. `PushDeliveryFailing` 은 "율"이 아니라 건수다 — 분모(시도 수) 지표가 없고, 있는 지표는 재시도를 전부 소진해 `failed` 로 굳은 건수뿐이다.

**R46-FIXOPS 추가 6종(`Ruling 640`) 읽는 법**

- 새 규칙은 전부 스크레이프 `job="backend"` 로 걸러 **부하 시험 프로파일을 보지 않는다** — 부하 시험 앱은 별도 관측 스택(`LOAD_TESTING §6`)이 다른 `job` 이름으로 긁고, 그쪽은 풀 10 · 연결 대기 100~190 · 서킷 실패 주입이 정상이다
- ⚠ **`BackendDown` 이 울리는 동안 위 `RunUnconfirmed` · `RunPositionLost` · `PushDeliveryFailing` 은 침묵이 정상이 아니다** — 백엔드가 죽으면 Prometheus 가 그 시계열을 즉시 끝난 것으로 표시해 **이미 발화 중이던 경보도 해소로 바뀐다.** 그 사이 미확정 회차·위치 유실·푸시 실패는 지표로 볼 수 없다
- `SchedulerStalled` 의 숫자(90초·30초)는 스케줄러 주기(`poll-interval-ms` 30초 · 10초)의 3배다 — 주기를 바꾸면 규칙의 숫자도 같이 바꾼다(`alerts.yml` 주석). 앱을 재기동하면 경과가 0 부터 다시 세어지므로 **`RetentionCleanupStalled` 는 3일 안에 한 번이라도 재배포하면 못 본다** — 정리가 계속 실패하는 경우는 건별 실패 카운터(`schoolbus_scheduler_failures_total`)가 따로 있다
- 5xx 비율은 `/actuator/*` 요청을 뺀다(스크레이프·헬스체크가 요청 수에 늘 섞이고 Redis 장애 때 헬스가 503). 분당 6건 미만이면 판정하지 않는다 — 새벽에 한두 건 실패한 것이 비율 50% 로 보여 울리는 것을 막는 하한이다

**울렸을 때 첫 확인**

| 경보 | 첫 확인 |
|---|---|
| `BackendDown` | `docker compose ps backend` · `docker compose logs --tail 120 backend`. 재시작이 반복이면 `RestartCount`(§8 · §11.6) |
| `Http5xxRatioHigh` | Grafana 대시보드의 오류 패널 → `docker compose logs backend \| grep ERROR`. `HikariPoolWaiting` 이 같이 울리면 연결 풀 문제 |
| `HikariPoolWaiting` | §11.5 의 쿼리 통계로 느린 쿼리 → `SELECT state, count(*) FROM pg_stat_activity GROUP BY 1`(`idle in transaction` 이 쌓이면 트랜잭션이 안 끝나는 곳) |
| `CircuitBreakerNotClosed` | §8 "지도 API 장애" 행 — 직선거리 근사로 계속 확정되는 상태 |
| `SchedulerStalled` | `schoolbus_scheduler_last_success_age_seconds` 가 계속 느는 스케줄러 이름을 보고 로그에서 그 클래스의 예외를 찾는다. 푸시 발송이 외부에서 멈춘 것이면 아웃박스가 길어진다(`FCM`) |
| `RetentionCleanupStalled` | 새벽 00:15 전후 로그에서 `retention` 예외 · 마지막 재배포 시각(3일 안이면 이 경보는 못 본다) |
| `RunPositionPartitionStalled` | 새벽 00:10 전후 로그에서 `run-position-partition` 예외 · `schoolbus_scheduler_failures_total` · 마지막 재배포 시각(재기동하면 경과가 0 부터 다시 세어져 3일 안에 재배포하면 이 경보는 못 본다) |
| `StaleMovingRun` | 아래 "끝나지 않은 이동 중 회차 처리" 절차 |
| `StompSessionsNearCap` | Grafana "4. 파이프라인 생존" 의 "활성 STOMP 세션" 모양 — 계단이면 이용자 증가, 톱니이면 재연결 반복. 실제 연결 수는 `tomcat_connections_current_connections`(세션 수보다 큰 만큼이 진행 중 HTTP 요청). 판단·대응은 §11.7 |

**끝나지 않은 이동 중 회차 처리(`StaleMovingRun`, `Ruling 701`·`702`)** — 하원 회차는 마지막 지점에 도착해도 잔류 인원이 있으면 종료를 보류하고(`finish_pending`), 잔류가 0 이 되는 순간에만 끝난다. 그 회차를 맡은 동승자가 하차 처리를 못 하면 영구히 `moving` 으로 남는다. **시스템은 자동으로 끝내지 않는다**(사양 C-15 — 잔류 인원이 남은 회차를 임의로 끝내면 안 됨). **관리자·관계자가 이 회차를 끝낼 수 있는 화면·API 는 아직 없다**(회차 취소도 `moving` 은 거부 — `RunCancellation`) — 현재 처리 수단은 아래 DB 직접 갱신이다. 이 회차는 근접 판정 · 위치 유실 경보 · 노선 승하차지 좌표 잠금에서 이미 빠져 있어 **방치해도 다른 기능을 막지는 않는다**(경보와 관제 목록에만 남는다).

1. 대상과 남은 승객을 확인한다(`sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env exec postgres psql -U schoolbus schoolbus` 로 접속):

   ```sql
   SELECT r.id, r.academy_id, r.service_date, r.direction, r.finish_pending,
          count(rr.id) FILTER (WHERE rr.status = 'boarded') AS still_boarded
   FROM run r LEFT JOIN run_rider rr ON rr.run_id = r.id
   WHERE r.status = 'moving' AND r.canceled_at IS NULL
     AND r.service_date < (now() AT TIME ZONE 'Asia/Seoul')::date - 1
   GROUP BY r.id ORDER BY r.service_date;
   ```

2. 학원에 확인한다 — 그 회차는 이미 지난 운행이다. 남은 승객(`still_boarded`)이 실제로 하차했는지는 학원이 안다.
3. 끝낸다 — **한 건씩, 확인한 id 로만**. 알림·하차 기록은 만들지 않는다(옛 운행이라 학부모에게 보낼 일이 아니다):

   ```sql
   UPDATE run SET status = 'finished', finished_at = now(), finish_pending = false
   WHERE id = <확인한 회차 id> AND status = 'moving' AND service_date < (now() AT TIME ZONE 'Asia/Seoul')::date - 1;
   ```

4. 게이지는 10분마다 갱신되므로 경보는 그 뒤 해소된다. 같은 회차가 반복되면 원인(동승자가 하차를 못 누르는 화면 흐름)을 따로 조사한다. 관리자 강제 종료 API 는 사양 변경이라 사용자 결정 뒤에 만든다.

### 11.3 경보 수신 — 텔레그램 봇 + 이메일 예비 (`Ruling 480 ④` · `483`)

Alertmanager 가 발화한 경보를 **텔레그램**으로 보내고 **이메일**을 예비 채널로 둔다. 두 채널은 한 수신자에 함께 있어 한쪽이 막혀도 다른 쪽으로 닿는다. 토큰·주소·비밀번호는 저장소(공개)에 없고 **SSM 선택 항목**(§3)이다 — **하나도 등록하지 않으면 수신자 없이 뜬다**(기동은 되고 알림이 어디로도 가지 않는다 — 그 상태는 §11.1 의 Prometheus `/alerts` 에서만 보인다).

| 구성 | 위치 |
|---|---|
| 설정 파일 | `/opt/school-bus/alertmanager/alertmanager.yml` — `deploy.sh` 가 매 배포마다 `infra/scripts/render-alertmanager.sh` 로 새로 만든다(저장소 밖 · 비밀값 포함 · `.gitignore` 의 `/alertmanager/`). Alertmanager 설정이 환경변수를 읽지 못해 파일을 만드는 방식이다 |
| 값 검사 | 한 채널의 값이 **반쪽**이거나 형식이 틀리면 `deploy.sh` 가 컨테이너를 건드리기 전에 멈춘다 — 반쪽 설정은 Alertmanager 기동 실패(= 경보 경로 전체 정지)다. 값은 출력하지 않는다 |
| 반영 | 배포 때 `docker compose kill -s HUP alertmanager` 로 새 설정을 읽힌다(컨테이너를 다시 만들지 않는다). 읽지 못하면 배포는 성공이고 경고 한 줄만 남는다 — `sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env logs --tail 30 alertmanager` 로 확인 |
| 규칙 | 모든 경보가 두 채널로 간다. 같은 경보는 4시간마다 다시 알린다(`repeat_interval`) · 해소도 알린다 |

**등록 절차(사용자 준비물 — 값 5종)**

1. **텔레그램 봇**: 텔레그램에서 `@BotFather` 에게 `/newbot` → 받은 토큰(`123456789:AAE...`)이 `ALERT_TELEGRAM_BOT_TOKEN`
2. **채팅 ID**: 그 봇을 받을 사람(또는 그룹)이 봇에게 아무 메시지나 보낸 뒤 로컬에서 `curl -s "https://api.telegram.org/bot<토큰>/getUpdates"` 의 `chat.id`(그룹은 음수)가 `ALERT_TELEGRAM_CHAT_ID`
3. **이메일**: SMTP 서버 주소·포트(예 `smtp.gmail.com:587`), 계정, 비밀번호(Gmail 은 앱 비밀번호) → `ALERT_SMTP_HOST` · `ALERT_SMTP_USER` · `ALERT_SMTP_PASSWORD`, 받을 주소가 `ALERT_EMAIL_TO`
4. SSM 에 등록(§3 의 `put-parameter` 방식 — 토큰·비밀번호는 `SecureString`) 후 배포한다

```bash
aws ssm put-parameter --name /school-bus/demo/ALERT_TELEGRAM_BOT_TOKEN --type SecureString --value '<토큰>'
aws ssm put-parameter --name /school-bus/demo/ALERT_TELEGRAM_CHAT_ID   --type String       --value '<채팅 ID>'
```

**수신 확인(§2.14 의 10번)** — 배포 뒤 EC2 에서 테스트 알림을 한 건 쏜다. 두 채널에 오면 통과다:

```bash
sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env exec -T alertmanager \
  amtool alert add TestAlert severity=warning --annotation=summary="수신 확인 — 무시해도 됨" --alertmanager.url=http://localhost:9093
```

**한계**: 이메일은 "텔레그램이 안 올 때를 위한 중복 전달"이지 별도 우선순위가 아니다. 서버(EC2)가 통째로 죽으면 Alertmanager 도 함께 죽어 아무것도 오지 않는다 — §11.4 의 외부 감시가 유일한 감지 수단이다.

### 11.4 외부 가동 감시 — `/healthz`

`https://api.<도메인>/healthz` 는 인터넷에서 닿는 유일한 헬스 주소다(UptimeRobot 같은 외부 감시용). backend 의 `/actuator/health` 를 그대로 프록시하므로 응답은 `{"status":"UP"}` 뿐이고(상세는 `show-details: never`), backend 나 DB·Redis 가 DOWN 이면 503, backend 가 죽으면 502·504 라 외부 감시가 실패를 본다. 정적 200 이 아니다 — 정적 200 은 backend 가 죽어도 초록이다. IP 당 분당 30회로 제한한다(초과 429). `/actuator` 의 나머지(`prometheus` 등)는 계속 404 다.

⚠ EC2 가 통째로 죽으면 Prometheus·Alertmanager 도 함께 죽는다 — 서버 사망은 이 외부 감시로만 알 수 있다(등록은 사용자 작업).


### 11.5 DB 쿼리 통계 — `pg_stat_statements` (`Ruling 644`)

운영 postgres 는 `shared_preload_libraries=pg_stat_statements` · `random_page_cost=1.1`(gp3 SSD — 기본 4 는 HDD 기준이라 플래너가 인덱스 스캔을 덜 고른다)로 뜬다(`docker-compose.prod.yml` · 스테이징 compose 도 같다). 어느 쿼리가 무거운지는 `idx_scan` 숫자가 아니라 이 확장이 답한다.

```bash
sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env exec -T postgres \
  psql -U schoolbus -d schoolbus -c "SELECT calls, round(total_exec_time) AS total_ms, round(mean_exec_time::numeric, 2) AS mean_ms, left(query, 100) AS query FROM ops_stats.pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10"
```

- **확장은 `public` 이 아니라 `ops_stats` 스키마에 있다.** 스테이징은 백엔드 기동·초기화마다 Flyway `clean()` 이 `public` 을 비우는데 `public` 에 만든 확장은 그때 같이 지워진다(2026-10-01 로컬 실측: `public` 에서는 사라지고 `ops_stats` 에서는 남음). 운영은 `clean()` 을 막지만 두 환경이 같은 이름으로 조회하도록 통일했다
- 확장 생성은 마이그레이션이 아니라 **postgres 초기화 스크립트**(`infra/postgres/init/01-pg-stat-statements.sql`)다 — 확장은 `shared_preload_libraries` 와 짝인 DB 서버 설정이라 앱 스키마 이력(Flyway)에 섞지 않는다. 빈 데이터 디렉터리로 **처음 뜰 때 한 번만** 실행된다(공식 이미지 규칙)
- ⚠ **이미 초기화된 볼륨**(이 설정이 들어가기 전에 한 번이라도 배포한 서버)은 스크립트가 돌지 않는다 — 이번 배포 뒤 한 번 손으로 만든다:
  ```bash
  sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env exec -T postgres \
    psql -U schoolbus -d schoolbus -c "CREATE SCHEMA IF NOT EXISTS ops_stats" -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements SCHEMA ops_stats"
  ```
- ⚠ **이 설정이 들어간 첫 배포는 postgres 컨테이너가 다시 만들어진다**(`command` 가 바뀌어 compose 가 재생성) — DB 가 수 초~수십 초 끊기고 백엔드 연결 풀이 다시 붙는다. 배포의 수십 초 다운타임(§10) 안이다
- 통계 초기화: `SELECT ops_stats.pg_stat_statements_reset()`. 오버헤드는 검토 문서의 추정(1~2%)이며 측정 전이다

### 11.6 컨테이너 안전장치 — OOM · 로그 · 응답 압축 (`Ruling 641` · `642` · `645`)

| 장치 | 값 | 이유 · 대가 |
|---|---|---|
| **OOM 이면 프로세스 종료** | backend `JAVA_TOOL_OPTIONS` 에 `-XX:+ExitOnOutOfMemoryError` | 이 플래그가 없으면 `OutOfMemoryError` 를 던진 스레드만 죽고 JVM 이 살아남아, 스케줄러·발송 스레드가 하나씩 사라진 **반쯤 죽은 서버**가 된다 — 컨테이너는 계속 "실행 중"이라 `restart: unless-stopped` 가 못 본다. 종료하면 재시작 정책이 되살린다(30~60초 다운). OOM 으로 끝난 컨테이너는 `RestartCount` 가 늘고 로그에 `OutOfMemoryError` 가 남는다 |
| **힙 덤프는 남기지 않는다** | `HeapDumpOnOutOfMemoryError` 부재(`OpsSettingsGuardTest` 가 운영에 들어오는 것을 막는다) | 덤프에는 학생·보호자 개인정보와 JWT 서명 키·FCM 개인 키가 그대로 담기고, 크기가 힙(약 2.15GB)이라 루트 디스크 30GB 를 재시작 반복 때 채운다. 원인 분석이 필요하면 스테이징에서 재현해 뜬다 |
| **로그 전송은 non-blocking** | `x-logging` 의 `mode: non-blocking` · `max-buffer-size: 25m` | 기본(blocking)이면 CloudWatch 전송이 느려지는 순간 컨테이너의 stdout 쓰기가 막혀 로그를 남기는 모든 스레드가 같이 멈춘다(Redis 장애 때 스택트레이스가 초당 수십 줄 나오는 상황이 원인이 된다). **대가: 버퍼가 차면 로그를 버린다** — 장애 때 일부 유실을 받아들인다. 옵션 이름은 `mode`·`max-buffer-size` 다(`awslogs-mode` 는 없다 — Docker 가 거부) |
| **JSON 응답 압축** | `nginx.prod.conf` `/api/` 의 `gzip on` · `gzip_types application/json` · `gzip_min_length 1024` | 관제 폴링처럼 키가 반복되는 JSON 은 크게 줄어든다(가짜 2.8KB 응답이 308B — 약 9배, 실제 관제 응답 크기는 미측정). 1KB 미만 · 이미지(사진) · WebSocket 은 대상이 아니다 |
| **인증 응답은 압축하지 않는다** | `/api/v1/auth/` 와 `= /api/v1/me/link-code` 는 `gzip off` | BREACH — 압축 크기가 응답 안의 비밀(토큰·연결 코드)과 공격자가 넣은 값의 일치를 드러낸다. 로그인·가입·복구·자녀 연결(속도 제한 location)은 압축을 켜지 않은 별도 location 이라 그대로 안전하다. `/auth/*` 전체와 연결 코드 발급이 해당한다 |

- **nginx 설정 변경은 배포가 자동 반영한다**(`Ruling 648`) — 설정은 파일 하나를 바인드 마운트하고 `s3 sync` 가 파일을 교체하면 컨테이너는 옛 파일을 계속 보지만(§11.2 의 Prometheus 와 같은 이유), `deploy.sh` 3-1 단계가 **새 설정을 `nginx -t` 로 검사한 뒤 proxy 를 다시 시작**한다. 검사가 실패하면 proxy 는 옛 설정으로 계속 응답하고 배포가 실패로 끝난다. 방식·끊김(1~2초)·한계는 §5.3. 로컬 `nginx -t` 는 첫 오류에서 멈추는데 설정 21번째 줄의 `upstream backend:8080` 과 인증서 경로가 로컬에는 없어, 그 뒤의 문법 오류까지 거르지 못한다 — 실제 검사는 배포 때 EC2 의 같은 네트워크·볼륨으로만 믿을 수 있다
- 압축 확인: `curl -s -D - -o /dev/null -H 'Accept-Encoding: gzip' https://api.<도메인>/api/v1/<JSON 목록 경로> -H 'Authorization: Bearer <토큰>' \| grep -i content-encoding` — 일반 조회는 `gzip`, `/api/v1/auth/refresh` 는 헤더가 없어야 한다

### 11.7 동시 연결 상한 — Tomcat `max-connections` (`Ruling 690` · `691`)

| 프로파일 | `server.tomcat.max-connections` | 이유 |
|---|---:|---|
| prod · demo | **4,000** | 목표 동시 세션(2,000)의 2배 · 최악 힙 약 1.05GB(운영 힙 약 2.15GB 의 49%) |
| staging | **1,000** | 팀원 체험 서버(동시 접속 수십 명) · 힙이 JVM 기본(호스트 메모리의 1/4) |
| local · load | 기본 8,192(명시 없음) | 부하 측정이 한계를 재야 한다 — `TomcatThreadPoolConfigTest` 가 이 프로파일 문서에 상한이 없음을 고정 |

**근거 계산** — 09-09 부하 측정 §4 표(`backend/report/2026-09-09-부하-한계-측정.md`, git 추적 밖)에서 직접 계산

- 세션당 힙 약 **0.13MB** — 500세션 339MB · 6,000세션 1,063MB 의 기울기 (1,063 − 339) ÷ 5,500. 세션 0 으로 외삽한 기준 힙 약 274MB
- 세션당 송신 버퍼 상한 **64KB**(`WebSocketConfig` `send-buffer-size-limit`) — 느린 클라이언트가 버퍼를 다 채우는 최악
- 최악 힙 ≈ 274MB + (0.13MB + 64KB) × 연결 수 → 기본 8,192 는 **약 1.85GB**(운영 힙 = 컨테이너 3,072MB × 70% ≈ 2.15GB 의 86%) · 4,000 은 **약 1.05GB**(49%)
- ⚠ 측정 한계 — 힙 최대값은 G1 이 회수를 미룬 쓰레기를 포함해 보수적이고(같은 보고서의 자원 실측 표에서 GC 직후 상주 힙은 135MB), 측정 기계(10코어)·무트래픽 세션 기준이라 4 vCPU 재측정 전까지 잠정(`Ruling 351` 과 같은 단서)

**상한에 닿으면** — Tomcat 11.0.22 소스(`Acceptor` · `LimitLatch` · `SocketWrapperBase`)로 확인

1. 연결 수가 상한이면 `Acceptor` 가 소켓을 받기 **전에** 멈춘다(`countUpOrAwaitConnection`) — 새 연결은 받지 못함
2. 받지 못한 연결은 OS 의 TCP 대기열(`accept-count` 100)에 쌓이고, 그 대기열이 차면 OS 가 새 연결을 거절하거나 응답하지 않는다 — 클라이언트는 연결 거부 또는 연결 시간 초과(웹 · Flutter 연결 한도 10초, `API_SPEC §7.2`)
3. **이미 맺어진 연결(세션 · 진행 중 요청)은 영향이 없다** — 막히는 것은 새 연결뿐이라 서버는 OOM 으로 죽지 않는다. 연결이 끊겨 자리가 나면 대기 중이던 연결부터 받는다. 클라이언트는 포기 없이 30초 상한 백오프로 재시도하므로(`API_SPEC §7.2`) 자리가 나면 이어서 붙는다
4. **WebSocket 도 같은 수에 든다** — 업그레이드는 새 연결이 아니라 같은 소켓을 계속 쓰고(`AbstractProtocol` 이 같은 `SocketWrapperBase` 를 업그레이드 처리기에 넘김), 수는 소켓이 닫힐 때(`SocketWrapperBase.close`)만 줄어든다 → 세션이 끊길 때까지 점유. REST 요청도 같은 한도 안에서 센다
5. 프록시(nginx)가 backend 로 여는 연결은 요청마다 닫힌다(`upstream backend_pool` 에 `keepalive` 없음) → 연결 수 ≈ WebSocket 세션 수 + 진행 중 HTTP 요청(요청 스레드 100 이하)
6. **서버는 거절을 기록하지 않는다** — 앱이 그 연결을 본 적이 없다. 그래서 상한 도달 전에 `StompSessionsNearCap`(3,000 = 상한의 75% · 5분 유지 · `job="backend"` 만)이 울린다

**끊긴 연결이 상한을 채우지 않는다** (`Ruling 693`·`694`) — 소리 없이 사라진 클라이언트(전원·망 끊김)의 세션은 하트비트를 협상했으면 무수신 30초, 하트비트를 껐거나 `CONNECT` 를 안 보냈으면 무수신 60초(`app.ws.idle-timeout-ms`, Tomcat 읽기 유휴 점검)에 서버가 닫아 연결 수에서 빠진다(점검 주기 10초라 30~40초 · 60~70초). 정리 경로 4가지와 실서버 시험은 `docs/ARCHITECTURE.md §10.4`. **하트비트를 끄는 클라이언트는 60초마다 아무 프레임이든 보내야 한다** — 서버가 방송을 계속 써도 클라이언트 프레임이 없으면 닫는다. 이 값(60초)은 모든 프로파일 공통이며 `TomcatThreadPoolConfigTest` 가 프로파일이 덮지 않음을 고정한다

**울렸을 때 판단** — 세션 수 그래프(§11.2 표)가 계단이면 이용자 증가라 상한·사양 재계산 대상이다. 위 공식으로 새 연결 수의 최악 힙을 구해 운영 힙(약 2.15GB) 안인지 보고 `application.yml` 의 prod·demo 상한을 올리되 **같은 변경에 경보 임계(상한의 75%)도 함께** 올린다 — `OpsSettingsGuardTest` 가 둘이 맞지 않으면 실패한다. 백엔드는 인스턴스 1대 고정이라(`ARCHITECTURE §9.5`) 한 대로 모자라면 사양을 올리거나 WS 브로커 릴레이를 먼저 만든다. 톱니이면 클라이언트의 재연결 반복(토큰 만료 · 망 불안정 · 앱 버전)이 원인이라 그쪽을 먼저 본다 — **재시작은 모든 세션을 끊어 재연결이 한꺼번에 몰리므로 마지막 수단**.

---

## 12. 관계자 웹 배포 — Vercel (2026-10-01 · `Ruling 481` · `502` · `503`)

관계자 웹(`frontend/apps/academy-web`, Next.js)은 **Vercel** 에서 배포한다(비용 이유 — 사용자 결정). 백엔드는 EC2 그대로다. 운영 compose·`nginx.prod.conf` 에 웹 컨테이너를 넣지 않고(시험 `DeploymentConfigGuardTest.prodServesNoWebFrontend`), API 서버의 `/` 는 404 다. 웹 변경 → `main` push → Vercel 이 빌드·배포하고 GitHub Actions 는 관여하지 않는다(옛 `deploy-web.yml` 은 되살리지 않는다).

### 12.1 구성

| | 웹 | API |
|---|---|---|
| 주소 | `https://app.<도메인>` | `https://api.<도메인>` |
| 서빙 | Vercel | EC2 + nginx(TLS) |
| 출처(origin) | 다름 | |
| 사이트(등록 도메인) | **같음**(`<도메인>`) | |

웹과 API 는 **다른 출처이지만 같은 사이트**다. 이 구분이 아래 인증 판정의 근거다.

### 12.2 인증·출처 판정 (`Ruling 502` · `503`)

2026-10-01 로컬 백엔드(`:8400` · 허용 출처 `https://app.example.com`)에서 실제 응답으로 확인한 값이다.

| 항목 | 판정 | 근거 |
|---|---|---|
| refresh 토큰 | 웹은 쿠키 `refresh_token` — `Secure; HttpOnly; SameSite=Strict; Path=/api/v1/auth`(응답 헤더 실측 · `RefreshTokenCookieAssembler`). **`SameSite=None` 으로 풀지 않는다** | `None` 은 외부 사이트가 일으킨 요청에도 쿠키가 붙어 CSRF 방어(`TECH_DECISIONS §2.4.3`)가 사라진다. 웹·API 가 같은 사이트라 `Strict` 가 그대로 성립한다 |
| 쿠키가 붙는 조건 | **웹이 API 와 같은 등록 도메인**이어야 한다 — `app.<도메인>` + `api.<도메인>` | Vercel 기본 주소(`*.vercel.app`)는 API 와 **다른 사이트**다. 규격상 `Strict` 쿠키는 다른 사이트의 요청에서 저장·전송되지 않아 로그인은 되는데 access 토큰(15분) 만료 뒤 갱신이 안 된다 — 규격에 따른 예상이며 실제 브라우저로는 확인하지 않았다 |
| access 토큰 | `Authorization: Bearer` 헤더(쿠키 아님). WebSocket 은 STOMP CONNECT 프레임 헤더 | 교차 출처의 영향이 없다 |
| CORS(REST) | `CORS_ALLOWED_ORIGINS` = `https://app.<도메인>` 하나. `/api/**` 에만 적용, 자격 증명 허용 | 실측: 허용 출처 preflight → `Access-Control-Allow-Origin` 일치 + `Access-Control-Allow-Credentials: true` · 허용 목록 밖(`*.vercel.app` 모양) → `403 Invalid CORS request`. 시험 `CorsCredentialsTest` |
| WebSocket | `WS_ALLOWED_ORIGIN_PATTERNS` = 같은 값. 이 값이 **유일한 방어선**(CORS 필터를 타지 않는다) | 실측: 허용 출처 → `101` · 밖 → `403` |
| **Vercel 미리보기 배포 주소** | **허용하지 않는다** | 주소가 가지·PR 마다 바뀌어 목록에 못 넣는다. 패턴(`https://*.vercel.app`)으로 열면 누구의 미리보기든 운영 API 를 쿠키와 함께 호출할 수 있다. 미리보기는 화면 확인용이다(API 호출이 CORS 로 막혀 로그인되지 않는다) — 로그인까지 보려면 스테이징(`STAGING.md`)을 쓴다 |
| 배포 검사 | `deploy.sh` 가 두 값에서 **와일드카드 · `http` · 경로 · `vercel.app`** 을 발견하면 컨테이너에 닿기 전에 멈춘다 | 시험 `DeployScriptGuardTest` |

출처를 바꾸는 절차: SSM 의 두 값을 같이 고친다 → 재배포(§5) → 브라우저 콘솔에 CORS 오류가 없는지(§2.14 의 7번). 한쪽만 고치면 REST 는 되는데 WebSocket 만 실패하는 형태가 된다(§8).

### 12.3 Vercel 프로젝트 설정

저장소 루트에 `vercel.json` 을 **두지 않는다** — 아래 설정을 Vercel 화면에서 한 번 하면 되고 파일로도 두면 두 곳이 어긋난다(필요해지면 그때 추가).

| 항목 | 값 |
|---|---|
| Import | GitHub `mskim98/School-Bus` |
| Framework Preset | Next.js |
| **Root Directory** | `frontend/apps/academy-web` |
| **Include source files outside of the Root Directory in the Build Step** | **켠다** — `globals.css` 가 `frontend/design-system/styles.css` 를 상대 경로로 가져온다(루트 밖) |
| Install / Build Command | 기본(`npm ci` / `npm run build`) |
| Node.js Version | 22.x (`Dockerfile` 과 같은 계열) |
| Production Branch | `main` |

**환경변수(Production)** — `NEXT_PUBLIC_*` 는 런타임이 아니라 **빌드 때 번들에 박힌다**. 바꾸면 재배포해야 반영된다.

| 이름 | 값 | 주의 |
|---|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | `https://api.<도메인>` | **스킴 포함 · 끝 `/` 없음**(`wsUrl.ts` 가 `https`→`wss` 로 바꿔 WebSocket 주소를 만든다). **빠뜨리면 빌드는 성공하고 번들에 `http://localhost:8080` 이 박혀** 로그인이 전부 실패한다 — 배포 뒤 브라우저 개발자 도구 Network 탭에서 로그인 요청이 `https://api.<도메인>/api/v1/auth/login` 으로 가는지 본다 |
| `NEXT_PUBLIC_NAVER_MAP_CLIENT_ID` | NCP Maps 웹 클라이언트 ID | 값은 브라우저 스크립트 주소에 실려 공개된다(설계상). 보호는 12.5 의 서비스 URL 등록이다 |
| `NEXT_PUBLIC_TEST_DATA_RESET` | **만들지 않는다** | 있으면 "테스트 데이터 초기화" 버튼이 켜진다(`Ruling 364` — 스테이징 전용) |

Preview 환경에는 값을 넣지 않아도 된다(화면 확인용 — §12.2).

### 12.4 도메인 연결

1. Vercel 프로젝트 → Settings → Domains → `app.<도메인>` 추가 → Vercel 이 안내하는 DNS 레코드(보통 `CNAME`)를 도메인 DNS 에 등록한다
2. 인증서는 Vercel 이 자동 발급한다
3. `api.<도메인>` A 레코드(§2.9)와 **같은 등록 도메인**인지 확인한다 — 다르면 §12.2 의 쿠키 조건이 성립하지 않는다
4. SSM `CORS_ALLOWED_ORIGINS` · `WS_ALLOWED_ORIGIN_PATTERNS` 를 `https://app.<도메인>` 으로 등록하고 배포한다(§3)

### 12.5 네이버 지도 키 — 서비스 URL 등록 (사용자 작업)

NCP 콘솔 → AI·NAVER API → Maps Application → 웹 서비스 URL 에 **`https://app.<도메인>` 을 추가한다.** 빠뜨리면 지도 SDK 인증(`/v3/auth`)이 401 로 거절돼 지도 화면에 "지도를 불러오지 못했습니다" 만 나온다(2026-09-18 로컬에서 포트가 다를 때 실제로 겪은 증상). 서버가 부르는 Directions·Geocoding 키는 서버-서버 호출이라 서비스 URL 과 무관하다.

### 12.6 롤백

Vercel 프로젝트 → Deployments → 직전 정상 배포 → **Promote to Production**(재빌드 없이 즉시 되돌린다). 웹과 API 는 따로 배포되므로 API 계약이 바뀐 변경은 **백엔드를 먼저, 웹을 나중에** 배포하고, 롤백은 반대로 웹을 먼저 되돌린다.

### 12.7 첫 Vercel 빌드에서 확인할 것

- `next.config.ts` 의 `output: "standalone"`(Docker 용)이 Vercel 빌드에서 무시되는지 — 실패하면 환경변수 조건으로 분기한다. 이 저장소에서는 Vercel 빌드를 실행해 본 적이 없다
- 요금제: Vercel 무료(Hobby) 요금제는 약관상 개인·비상업 용도로 알고 있다 — 학원 운영 서비스는 유료(Pro)가 필요할 수 있다. 가입 전에 현재 약관을 확인한다

---

## 13. 운영 규칙 (`Ruling 480` ⑤⑥⑦ · `504`~`506`)

### 13.1 메인 관리자는 2개 이상 (`Ruling 504`)

**규칙**: 운영 시작 전에 **활성 메인 관리자를 2명 이상** 둔다. 서로 다른 사람이 각자 비밀번호를 쓰고, 계정을 공유하지 않는다.

**이유**: 로그인 5회 실패 시 계정이 차단되는 규칙은 **모든 역할**에 걸리고(`API_SPEC §2.5`), 해제는 메인 관리자만 한다. 유일한 메인 관리자의 아이디만 알면 누구나 그 계정을 잠글 수 있고, 잠기면 해제할 사람이 없어 DB 를 직접 고쳐야 한다(운영자가 DB 를 직접 고치면 허용되지 않은 전이가 통과하고 이력이 남지 않는다 — `TECH_DECISIONS §14.3`).

**두 번째 계정을 만드는 법.** 가입 경로가 없고(`API_SPEC §2.2` — 메인 관리자는 내부 발급), SSM 첫 관리자 값(§3)은 **메인 관리자가 0명일 때만** 쓰이므로 두 번째는 DB 에 직접 넣는다. 한 트랜잭션이라 중간에 실패하면 아무것도 남지 않는다.

1. 비밀번호 해시를 만든다(관리자 계정은 비용을 올린다): `docker run --rm httpd:2.4-alpine htpasswd -nbB -C 12 x '<초기 비밀번호>' | cut -d: -f2`
2. EC2 에서(SSM Session Manager 접속 후) 실행한다 — 아이디·이름·해시만 바꾼다:

```bash
sudo docker compose -f /opt/school-bus/docker-compose.prod.yml --env-file /opt/school-bus/.env \
  exec -T postgres psql -U schoolbus schoolbus -v ON_ERROR_STOP=1 -1 \
  -v login='<아이디>' -v hash='<1번의 해시>' -v name='<이름>' <<'SQL'
WITH new_account AS (
  INSERT INTO account (login_id, password_hash, name, phone, role, status)
  VALUES (:'login', :'hash', :'name', '미등록', 'system_admin', 'active')
  RETURNING id
)
INSERT INTO system_admin (account_id) SELECT id FROM new_account;
SQL
```

3. 새 계정으로 로그인해 **초기 비밀번호를 바꾼다**(해시를 만든 사람이 초기 비밀번호를 안다). 메인 관리자 전용 화면이 열리는지 본다
4. 이 입력은 `audit_log` 에 남지 않는다 — 누가·언제·왜 만들었는지를 운영 기록에 직접 적는다

2026-10-01 로컬 DB 에서 이 SQL 로 계정을 만들고 로그인(200)·오답(401)·메인 관리자 전용 API(200)를 확인했다.

### 13.2 비용 한도 알림 — AWS Budgets (`Ruling 505`)

금액은 **배포 때** 정한다(사양을 올리면 월 비용이 몇 배가 된다 — 현재 `t3.medium` 기준 월 약 $45 는 Kafka 제거 전 추정이며 권장 사양으로 다시 계산하지 않았다). 알림은 이메일 둘 — 실제 지출이 한도의 80% 를 넘을 때, 이번 달 예상 지출이 한도의 100% 를 넘을 때.

```bash
cat > budget.json <<'JSON'
{
  "BudgetName": "school-bus-monthly",
  "BudgetLimit": {"Amount": "<월 한도 USD>", "Unit": "USD"},
  "TimeUnit": "MONTHLY",
  "BudgetType": "COST"
}
JSON
cat > budget-notifications.json <<'JSON'
[
  {"Notification": {"NotificationType": "ACTUAL", "ComparisonOperator": "GREATER_THAN",
                    "Threshold": 80, "ThresholdType": "PERCENTAGE"},
   "Subscribers": [{"SubscriptionType": "EMAIL", "Address": "<운영담당 이메일>"}]},
  {"Notification": {"NotificationType": "FORECASTED", "ComparisonOperator": "GREATER_THAN",
                    "Threshold": 100, "ThresholdType": "PERCENTAGE"},
   "Subscribers": [{"SubscriptionType": "EMAIL", "Address": "<운영담당 이메일>"}]}
]
JSON
aws budgets create-budget --account-id <계정ID> --budget file://budget.json \
  --notifications-with-subscribers file://budget-notifications.json
```

AWS 는 한도를 넘어도 서비스를 멈추지 않는다 — **알림만** 한다. 외부 API 과금(네이버 Directions 의 일일 한도 · 초과 시 차단인지 과금인지)은 AWS 밖이라 NCP 콘솔에서 따로 확인한다(사용자 작업).

### 13.3 저장소 공개 유지 (`Ruling 506`)

저장소 `mskim98/School-Bus` 는 **공개를 유지한다**(사용자 확정). 공개라서 지켜야 할 것:

- 비밀값을 커밋하지 않는다 — `backend/.env` · `.env.local` · SSM 값 · `.htpasswd` · 렌더된 `alertmanager/` 는 `.gitignore` 에 있다. 새 비밀 파일을 만들면 먼저 무시 목록에 넣는다
- Actions 로그도 공개다 — `ci.yml` 은 비밀 없이 돌고 로그를 낮춘다(§5.1). `deploy-backend.yml` 은 실패 때 컨테이너 로그를 찍지 않는다
- push 전에 실제 비밀값이 커밋에 없는지 확인한다(`CLAUDE.md`). 2026-10-01 점검 방법과 결과는 `docs/IMPLEMENTATION_PLAN.md §8.79` 에 있다

## 14. 외부 연동 준비물 (2026-10-01 · R46-INTEG · `Ruling 483` · `510~513`)

**외부 연동은 자리만 만들어 뒀다** — 포트(인터페이스) + 키가 없을 때 쓰는 기본 구현 + 설정 키 이름. 키·계정이 없어도 백엔드·웹·앱 4곳의 빌드와 시험은 그대로 통과한다. 아래는 **사용자가 준비할 것**과 **어디에 넣는가**다. 설정 키 이름은 코드에 실제로 있는 이름이다(`grep` 확인은 `IMPLEMENTATION_PLAN §8.80`).

| 연동 | 사용자가 준비할 것 | 설정 키 이름(코드) | 넣는 곳 | 준비 전 동작 |
|---|---|---|---|---|
| 푸시 발송(서버) | Firebase 프로젝트 · 서비스 계정 키(JSON) | `app.push.sender=fcm` · `app.push.fcm.project-id` · `app.push.fcm.client-email` · `app.push.fcm.private-key` ← 환경변수 `FCM_PROJECT_ID` · `FCM_CLIENT_EMAIL` · `FCM_PRIVATE_KEY` | SSM `/school-bus/demo/FCM_*` 3개(§3 — `prod` 에서만 필수) | `prod`: 기동 실패(의도). `local`·`demo`: `LoggingPushSender`(로그만) |
| 푸시 수신(Android 앱 2종) | Firebase 에 앱 2종(학부모·학생 / 매니저) 등록 → `google-services.json` 2개 | (파일) | 각 앱 `android/app/` — **공개 저장소라 커밋하지 않고** 빌드 때 CI 시크릿으로 복원 | 앱이 기기별 자리표시 토큰(`placeholder-<기기 식별자>`)을 등록 — 서버가 FCM 거부를 받아 해지(`Ruling 331`) · 알림 목록·앱 안 갱신은 정상 |
| 푸시 수신(iOS 앱 2종) | Apple Developer 계정 · APNs 인증 키(`.p8` · 키 ID · 팀 ID) → Firebase 콘솔에 업로드 · `GoogleService-Info.plist` 2개 | (파일) | 각 앱 `ios/Runner/` — 위와 같이 커밋하지 않음. Xcode 에서 Push Notifications capability 추가 | 위와 같음 |
| 앱 코드 전환 | 위 파일 준비 뒤 `firebase_core` · `firebase_messaging` 의존성 추가 · 앱 시작 때 `Firebase.initializeApp()` · `FirebasePushTokenSource` 작성 | `pushTokenSourceProvider`(parent-app · manager-app `lib/app/di.dart`) | 두 `di.dart` 의 이 provider 한 곳만 교체 — 로그인 뒤 등록 · 로그아웃 해지는 이미 `AuthApi` 가 한다 | `PlaceholderPushTokenSource`(기본) |
| 웹 브라우저 푸시(VAPID) | **불필요** — 사양에 관계자 웹 브라우저 푸시가 없다(`Ruling 511`) | — | — | — |
| 문자(SMS) | 업체 선정(알리고 · 솔라피 · NCP SENS 등) · 계정 · API 키 · **발신번호 사전 등록**(통신사 인증 필요) | `app.sms.sender`(값 없음 = 비활성 · `logging` = 개발용 로그) · 업체 구현체의 키 이름은 구현 때 정한다 | 업체 구현체를 더할 때 `application.yml` 키 + `docker-compose.prod.yml` 환경변수 + SSM(§3)을 함께 추가 | 비활성: `POST /auth/recover` 는 `503 RECOVERY_UNAVAILABLE`(복구는 관리자 경유 `API_SPEC §5.22`) |
| 이메일 | **없음** — 이메일을 보내는 기능이 사양에 없다(`Ruling 511`). 경보 이메일은 §11.3 Alertmanager 몫 | — | — | — |

**Firebase 구현체 스케치(검증 전 — 배포 때 최신 FlutterFire 문서로 확인).** 포트는 `PushTokenSource`(`baraeda_core`)이고 구현은 두 메서드다.

```dart
class FirebasePushTokenSource implements PushTokenSource {
  @override
  Future<String?> currentToken() async {
    final settings = await FirebaseMessaging.instance.requestPermission();
    if (settings.authorizationStatus == AuthorizationStatus.denied) return null; // 권한 거부 → 등록 생략
    return FirebaseMessaging.instance.getToken();
  }

  @override
  Stream<void> get onMessage => FirebaseMessaging.onMessage.map((_) {}); // 알림 목록 갱신 연결 자리
}
```

`onMessage` 를 알림 목록 갱신에 잇는 곳은 학부모 앱 홈의 주기 갱신(`Ruling 431` 90초)과 같은 자리다 — `ref.invalidate(...)` 한 줄이며, 이어 붙이면 주기를 더 늘릴 수 있다. `google-services.json` · `GoogleService-Info.plist` 가 없는 빌드에서 `Firebase.initializeApp()` 이 실패하므로, 파일이 준비되기 전에는 의존성도 넣지 않는다(`Ruling 510`).
