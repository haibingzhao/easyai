package com.easy.easyai.core.host

/**
 * A startup step that must finish before the passes acting on owned rows run.
 *
 * The one producer today is legacy-owner adoption: rows written before `owner_app` existed are
 * inert until claimed, so an orphan sweep that runs first sees none of them and the claimed
 * PENDING/RUNNING tasks then stay "running" until the next restart. The sweep awaits this gate
 * instead of racing the adopter.
 *
 * A host without an adopter simply has no such bean; callers treat an absent gate as already open.
 */
interface HostStartupGate {

    /** Returns once the startup step finished, successfully or not. */
    suspend fun awaitReady()
}
