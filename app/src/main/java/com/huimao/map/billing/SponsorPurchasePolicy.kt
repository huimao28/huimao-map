package com.huimao.map.billing

import com.android.billingclient.api.Purchase

/**
 * 赞助购买的纯决策逻辑（不依赖 BillingClient，可单测）。
 */
object SponsorPurchasePolicy {
    /**
     * 是否应对这笔购买执行 consume：只有「已付款成功且尚未确认」的购买
     * 才 consume，做成可重复购买的 consumable 模式。
     */
    fun shouldConsume(purchaseState: Int, isAcknowledged: Boolean): Boolean =
        purchaseState == Purchase.PurchaseState.PURCHASED && !isAcknowledged

    /** 从一笔购买携带的商品 ID 列表中识别赞助档位。 */
    fun tierOf(productIds: List<String>): SponsorTier? =
        productIds.firstNotNullOfOrNull(SponsorTier::fromProductId)
}
