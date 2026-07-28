<#
.SYNOPSIS
    개발용 채번 카운터 리셋 도구. (설계 결정 D-10)

.DESCRIPTION
    ORDER_ID / SHIPMENT_ID 채번 공간은 [A-Z][0-9]{3} = 26,000개로 유한하다.
    개발 중 반복 실행으로 소진되면 애플리케이션은 EAI-4003 으로 정직하게 실패한다.

    리셋 기능을 애플리케이션 안에 두지 않은 것은 의도다. 카운터를 되돌리는 경로가
    앱 안에 있으면 언젠가 설정 실수로 운영에서 실행되고, 그 결과는 예외가 아니라
    "같은 번호가 두 번 발급되는" 조용한 데이터 사고로 나타난다.

    ※ 이 도구는 Redis 카운터만 되돌린다. 이미 적재된 ORDER_TB 행은 그대로 남아 있으므로,
      리셋 후 같은 번호로 다시 적재하면 PK 위반이 난다.
      실제로 처음부터 다시 돌리려면 대상 테이블의 본인 APPLICANT_KEY 행도 함께 정리할 것.

    ※ 이 파일은 UTF-8 with BOM 으로 저장해야 한다. PowerShell 5.1 은 BOM 이 없는 .ps1 을
      시스템 ANSI 코드페이지(한글 Windows = MS949)로 읽어, 파싱 시점에 한글 리터럴이 깨진다.
      콘솔 코드페이지(chcp)를 바꿔도 소용없다 — 출력이 아니라 소스 읽기 단계의 문제다.

.PARAMETER Target
    order | shipment | all (기본 all)

.PARAMETER Container
    Redis 컨테이너 이름 (기본 inspien-redis)

.PARAMETER Force
    확인 프롬프트를 건너뛴다.

.EXAMPLE
    .\tools\reset-sequence.ps1
    .\tools\reset-sequence.ps1 -Target order -Force
#>
[CmdletBinding()]
param(
    [ValidateSet('order', 'shipment', 'all')]
    [string]$Target = 'all',

    [string]$Container = 'inspien-redis',

    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# docker 가 돌려주는 바이트를 UTF-8 로 해석한다. 기본값은 콘솔 코드페이지라
# 컨테이너 쪽 출력에 비ASCII 가 섞이면 깨진다.
$previousEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$keys = switch ($Target) {
    'order'    { @('eai:seq:order') }
    'shipment' { @('eai:seq:shipment') }
    'all'      { @('eai:seq:order', 'eai:seq:shipment') }
}

function Invoke-Redis {
    param([string[]]$RedisArgs)
    $output = & docker exec $Container redis-cli @RedisArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "redis-cli 실행 실패 (컨테이너: $Container). 'docker compose up -d' 로 띄웠는지 확인할 것.`n$output"
    }
    return $output
}

try {
    Write-Host "대상 컨테이너 : $Container"
    Write-Host "대상 키       : $($keys -join ', ')"
    Write-Host ''

    # 현재 값을 먼저 보여준다. 얼마나 소비했는지 모른 채 지우면 원인 파악이 불가능해진다.
    Write-Host '현재 카운터:'
    foreach ($key in $keys) {
        $value = (Invoke-Redis @('GET', $key)) -join ''
        if ([string]::IsNullOrWhiteSpace($value)) {
            Write-Host ("  {0,-22} (없음)" -f $key)
        }
        else {
            $used = [int]$value
            $remain = 26000 - $used
            Write-Host ("  {0,-22} {1} 개 사용 / {2} 개 남음" -f $key, $used, $remain)
        }
    }
    Write-Host ''

    if (-not $Force) {
        $answer = Read-Host '카운터를 0으로 되돌린다. 계속하려면 yes 입력'
        if ($answer -ne 'yes') {
            Write-Host '취소했다.' -ForegroundColor Yellow
            return
        }
    }

    $deleted = (Invoke-Redis (@('DEL') + $keys)) -join ''
    Write-Host ''
    Write-Host "리셋 완료. 삭제된 키 $deleted 개 — 다음 채번은 A000 부터 시작한다." -ForegroundColor Green
}
finally {
    [Console]::OutputEncoding = $previousEncoding
}
