import { useEffect, useRef } from 'react';
import { TurnStreamRegistry } from '../stream/TurnStreamRegistry.js';
/** 按会话管理执行流注册、替换与释放，并同步该会话的观察标记。 */
export function useTurnStream(setObserving) {
    const registry = useRef(null);
    if (!registry.current) registry.current = new TurnStreamRegistry(setObserving);
    registry.current.onObserving = setObserving;
    useEffect(() => {
        const streams = registry.current;
        return () => streams.close();
    }, []);
    return registry.current;
}
