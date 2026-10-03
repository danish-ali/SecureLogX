package com.securelogx.log4j2;

import com.securelogx.api.MaskedResult;

import java.util.List;

@FunctionalInterface
interface SecureMaskingService {
    List<MaskedResult> maskAll(List<String> texts);
}
