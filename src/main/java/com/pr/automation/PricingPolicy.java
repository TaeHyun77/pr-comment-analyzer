package com.pr.automation;

// 가격 계산 정책. checkout 흐름에서 OrderService가 호출한다.
public class PricingPolicy {

    // 단가 * 수량
    public int subtotal(int unitPrice, int quantity) {
        return unitPrice * quantity;
    }

    // 주의: 반환값은 "할인 금액(차감액)"이다. 할인 후 가격이 아니다.
    public int discountAmount(int subtotal, int discountRate) {
        return subtotal * discountRate / 100;
    }

    // 부가세 10% 포함 금액
    public int withTax(int price) {
        return price + price / 10;
    }
}
