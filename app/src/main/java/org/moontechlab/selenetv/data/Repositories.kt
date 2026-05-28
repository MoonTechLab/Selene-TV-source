package org.moontechlab.selenetv.data

import org.moontechlab.selenetv.data.local.LocalSourcesRepository
import org.moontechlab.selenetv.data.local.LocalUserDataRepository
import org.moontechlab.selenetv.data.server.ServerSourcesRepository
import org.moontechlab.selenetv.data.server.ServerUserDataRepository
import org.moontechlab.selenetv.service.AppDataService

/**
 * 仓库工厂：每次调用按当前模式（AppDataService.isLocalModeSync）路由到对应实现。
 *
 * 不缓存实例 —— 模式切换后下次调用即得新实现，无须刷新调用方。
 */
object Repositories {
    fun userData(): UserDataRepository =
        if (AppDataService.isLocalModeSync()) LocalUserDataRepository else ServerUserDataRepository

    fun sources(): SourcesRepository =
        if (AppDataService.isLocalModeSync()) LocalSourcesRepository else ServerSourcesRepository
}
