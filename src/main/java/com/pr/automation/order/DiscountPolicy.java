package com.pr.automation.order;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DiscountPolicy {

    // PREMIUM=20%, GOLD=10% 할인 금액 산출
    public long calculateDiscount(long basePrice, String memberGrade) {
        int percent;
        if ("PREMIUM".equals(memberGrade)) {
            percent = 20;
        } else if ("GOLD".equals(memberGrade)) {
            percent = 10;
        } else {
            percent = 0;
        }
        return basePrice * percent;
    }
}
