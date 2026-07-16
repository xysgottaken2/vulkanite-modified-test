package me.cortex.vulkanite.lib.other;

import java.util.Optional;

public class NullUtil {
    /**
     * 将嵌套的 Optional 扁平化
     */
    public static <T> Optional<T> flatten(Optional<Optional<T>> nested) {
        // 加个安全防御：防止 nested 容器本身就是 null
        // 如果 nested 非空，直接返回其内部的 Optional，否则返回空 Optional
        return nested == null ? Optional.empty() : nested.orElse(Optional.empty());
    }
}
