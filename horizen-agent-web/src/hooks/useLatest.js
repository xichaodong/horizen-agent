import {useRef} from 'react';

/** Latest callbacks/data without restarting an owned network subscription on every render. */
/** 保留最新值的稳定引用，使异步回调能够读取当前状态而不依赖旧闭包。 */
export function useLatest(value) {
    const latest = useRef(value);
    latest.current = value;
    return latest;
}
