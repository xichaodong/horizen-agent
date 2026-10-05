/** One observation owner per session. A stale completion cannot release a newer owner. */
/** 每个会话只持有一个当前观察控制器，替换时中断旧观察，旧回调不能释放新订阅。 */
export class TurnStreamRegistry {
    constructor(onObserving) {
        this.controllers = new Map();
        this.onObserving = onObserving;
    }
    begin(sessionId, observing = true) {
        this.controllers.get(sessionId)?.abort();
        const controller = new AbortController();
        this.controllers.set(sessionId, controller);
        this.onObserving(sessionId, observing);
        return controller;
    }
    markRunning(sessionId, controller) {
        if (this.controllers.get(sessionId) === controller) this.onObserving(sessionId, true);
    }
    finish(sessionId, controller) {
        if (this.controllers.get(sessionId) !== controller) return;
        this.controllers.delete(sessionId);
        this.onObserving(sessionId, false);
    }
    stop(sessionId) {
        const controller = this.controllers.get(sessionId);
        controller?.abort();
        if (controller) this.finish(sessionId, controller);
    }
    close() {
        for (const sessionId of this.controllers.keys()) this.stop(sessionId);
    }
}
