package com.dayforge.data.repository

/** Local activation barrier, not a server error code or an automatic data-reset request. */
class ProtocolNextDataRequiresUpgradeException : IllegalStateException(
    "本地包含新版事项数据，不能使用当前同步协议处理；请完成新版功能启用后再同步"
)
