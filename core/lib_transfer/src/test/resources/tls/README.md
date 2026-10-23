# TLS 测试 fixture

nas-test.p12 与 expired-test.p12 由 keytool 生成，仅供本机 MockWebServer 测试，不包含任何用户凭据或 NAS 证书。固定密码 test-only 为公开的测试用途密码，别名 test。有效证书起始于 2025-01-01、有效期 3650 天；过期证书起始于 2020-01-01、有效期 1 天。证书 CN 使用 .invalid 域名，故 localhost 测试覆盖主机名不匹配场景。不得用于生产。
