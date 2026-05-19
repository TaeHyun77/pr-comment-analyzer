package com.pr.automation;

// 주문 결제 흐름. 이 파일의 checkout 라인에 PR 리뷰 코멘트를 단다.
public class OrderService {

    private final PricingPolicy pricing = new PricingPolicy();

    // 주문 최종 결제 금액 계산
    public int checkout(int unitPrice, int quantity, int discountRate) {
        int subtotal = pricing.subtotal(unitPrice, quantity);
        int discounted = pricing.discountAmount(subtotal, discountRate); // 버그: 할인'액'을 할인'후 가격'처럼 사용
        return pricing.withTax(discounted);
    }
}
