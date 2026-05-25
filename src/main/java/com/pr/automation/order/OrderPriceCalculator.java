package com.pr.automation.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPriceCalculator {

    private final DiscountPolicy discountPolicy;

    public long calculateFinalPrice(long basePrice, String memberGrade) {
        long discount = discountPolicy.calculateDiscount(basePrice, memberGrade);
        return basePrice - discount;
    }

    public boolean isPremiumMember(String memberGrade) {
        return "PREMIUM".equals(memberGrade);
    }
}
