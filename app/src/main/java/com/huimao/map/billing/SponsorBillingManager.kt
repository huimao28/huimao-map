package com.huimao.map.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams

/**
 * 赞助内购管理器：连接计费服务、查询三个档位商品、拉起购买流程、
 * 购买成功后 consume（可重复购买）。
 *
 * 所有回调都保证在主线程投递；任何环节失败都走 [onError] 或返回空列表，
 * 不抛异常。
 */
class SponsorBillingManager(
    context: Context,
    private val onPurchaseSuccess: (SponsorTier) -> Unit,
    private val onError: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(
            PurchasesUpdatedListener { _, purchases ->
                purchases?.forEach { handlePurchase(it) }
            },
        )
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build(),
        )
        .build()

    private var connected = false

    /**
     * 连接计费服务；连接成功后查询三个赞助商品并回调。
     * 查不到商品（后台未创建/未激活）或连接失败时回调空列表。
     */
    fun connect(onProducts: (List<ProductDetails>) -> Unit) {
        runCatching {
            if (connected) {
                queryProducts(onProducts)
                return
            }
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        connected = true
                        queryProducts(onProducts)
                    } else {
                        postOnMain { onProducts(emptyList()) }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    connected = false
                }
            })
        }.onFailure {
            postOnMain { onProducts(emptyList()) }
        }
    }

    private fun queryProducts(onProducts: (List<ProductDetails>) -> Unit) {
        runCatching {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    SponsorTier.allProductIds.map { id ->
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    },
                )
                .build()
            billingClient.queryProductDetailsAsync(params) { result, productDetailsResult ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    val wanted = SponsorTier.allProductIds.toSet()
                    val ordered = productDetailsResult.productDetailsList
                        .filter { it.productId in wanted }
                        .sortedBy { SponsorTier.allProductIds.indexOf(it.productId) }
                    postOnMain { onProducts(ordered) }
                } else {
                    postOnMain { onProducts(emptyList()) }
                }
            }
        }.onFailure {
            postOnMain { onProducts(emptyList()) }
        }
    }

    /** 拉起 Google Play 购买流程。 */
    fun launchPurchase(activity: Activity, product: ProductDetails) {
        runCatching {
            if (!connected) {
                postOnMain(onError)
                return
            }
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(product)
                            .build(),
                    ),
                )
                .build()
            val result = billingClient.launchBillingFlow(activity, params)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                postOnMain(onError)
            }
        }.onFailure {
            postOnMain(onError)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        val tier = SponsorPurchasePolicy.tierOf(purchase.products) ?: return
        if (!SponsorPurchasePolicy.shouldConsume(purchase.purchaseState, purchase.isAcknowledged)) return
        runCatching {
            val params = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.consumeAsync(params) { result, _ ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    postOnMain { onPurchaseSuccess(tier) }
                }
            }
        }
    }

    fun destroy() {
        runCatching { billingClient.endConnection() }
        connected = false
    }

    private fun postOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
