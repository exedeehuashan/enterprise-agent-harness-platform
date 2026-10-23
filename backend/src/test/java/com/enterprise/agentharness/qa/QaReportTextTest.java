package com.enterprise.agentharness.qa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QaReportTextTest {
    @Test
    void untrustedTextCannotInjectMarkupLinksOrMentions() {
        assertThat(QaReportService.text("fix <script>alert(1)</script> & [click](http://x) @admin #12 | a_b *c*\n# heading"))
                .isEqualTo("fix &lt;script&gt;alert\\(1\\)&lt;/script&gt; &amp; \\[click\\]\\(http://x\\) @​admin \\#12 \\| a\\_b \\*c\\* \\# heading");
        assertThat(QaReportService.text("x".repeat(400))).hasSize(300).endsWith("…");
        assertThat(QaReportService.text(null)).isEmpty();
        assertThat(QaReportService.code("Order`Test#creates\nnext")).isEqualTo("Order'Test#creates next");
    }
}
