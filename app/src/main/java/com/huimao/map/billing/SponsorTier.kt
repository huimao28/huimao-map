package com.huimao.map.billing

/**
 * 赞助档位。商品 ID 固定不变，必须与 Play Console 后台
 * （创收 → 应用内商品）创建并激活的同名一次性商品一致。
 */
enum class SponsorTier(val productId: String) {
    COFFEE("sponsor_coffee"),
    LUNCH("sponsor_lunch"),
    FEAST("sponsor_feast");

    companion object {
        /** 商品 ID 列表（档位从低到高）。 */
        val allProductIds: List<String> = entries.map { it.productId }

        fun fromProductId(productId: String): SponsorTier? =
            entries.firstOrNull { it.productId == productId }
    }
}
