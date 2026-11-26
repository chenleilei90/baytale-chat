package com.baytale.baychat.core.push

import cn.jpush.android.service.JCommonService

/**
 * 极光后台连接与厂商通道适配服务（在独立 :pushcore 进程中运行，随应用声明 Manifest 占位生效）
 */
class BayPushService : JCommonService()
