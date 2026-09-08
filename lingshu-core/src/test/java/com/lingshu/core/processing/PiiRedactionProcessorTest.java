package com.lingshu.core.processing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiiRedactionProcessorTest {

    private final PiiRedactionProcessor processor = new PiiRedactionProcessor();

    @Test
    void redactsChinesePhoneIdCardAndEmail() {
        String source = "手机13800138000，身份证11010519491231002X，邮箱user@example.com";

        assertEquals("手机[PHONE]，身份证[ID_CARD]，邮箱[EMAIL]", processor.redact(source));
    }
}
