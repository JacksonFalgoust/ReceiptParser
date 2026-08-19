package com.jacksonfalgoust.receiptsplitter.receipt;

import org.springframework.stereotype.Component;

@Component
public class ReceiptParser {
    // Row-grouping by y-coordinate and trailing-price regex parsing are
    // added when OCR integration work begins. See ARCHITECTURE.md
    // ("Receipt OCR & Parsing Pipeline") for the algorithm this implements.
}
