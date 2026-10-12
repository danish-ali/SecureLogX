package com.securelogx.log4j2;

import com.securelogx.api.MaskedResult;
import com.securelogx.model.MaskingFieldOrigin;

import java.util.List;

@FunctionalInterface
interface SecureMaskingService {
    List<MaskedResult> maskAll(List<String> texts);

    /**
     * Adapter can pass trusted origin categories without breaking existing
     * implementations and model-free tests of this functional interface.
     */
    default List<MaskedResult> maskAllWithOrigins(
            List<String> texts,
            List<MaskingFieldOrigin> origins
    ) {
        return maskAll(texts);
    }
}
