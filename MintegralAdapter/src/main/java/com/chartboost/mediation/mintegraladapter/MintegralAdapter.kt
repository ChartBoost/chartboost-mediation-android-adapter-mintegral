/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.mintegraladapter

import android.app.Activity
import android.content.Context
import android.util.Size
import android.widget.FrameLayout
import com.chartboost.chartboostmediationsdk.ad.ChartboostMediationBannerAdView.ChartboostMediationBannerSize.Companion.asSize
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_GRANTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_UNKNOWN
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_GRANTED
import com.chartboost.core.consent.ConsentKey
import com.chartboost.core.consent.ConsentKeys
import com.chartboost.core.consent.ConsentManagementPlatform
import com.chartboost.core.consent.ConsentValue
import com.chartboost.core.consent.ConsentValues
import com.chartboost.mediation.mintegraladapter.MintegralAdapter.Companion.onShowFailure
import com.chartboost.mediation.mintegraladapter.MintegralAdapter.Companion.onShowSuccess
import com.mbridge.msdk.MBridgeConstans
import com.mbridge.msdk.interstitialvideo.out.InterstitialVideoListener
import com.mbridge.msdk.interstitialvideo.out.MBBidInterstitialVideoHandler
import com.mbridge.msdk.interstitialvideo.out.MBInterstitialVideoHandler
import com.mbridge.msdk.mbbid.out.BidManager
import com.mbridge.msdk.out.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation Mintegral Adapter.
 */
class MintegralAdapter : PartnerAdapter {
    companion object {
        /**
         * Lambda to be called for a successful Mintegral ad show.
         */
        internal var onShowSuccess: () -> Unit = {}

        /**
         * Lambda to be called for a failed Mintegral ad show.
         */
        internal var onShowFailure: () -> Unit = {}

        /**
         * Key for parsing the Mintegral app ID
         */
        private const val APP_ID_KEY = "mintegral_app_id"

        /**
         * Key for parsing the Mintegral app key
         */
        private const val APP_KEY_KEY = "app_key"
    }

    /**
     * The Mintegral adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = MintegralAdapterConfiguration

    /**
     * Track whether the Mintegral SDK has been successfully initialized.
     */
    private var isSdkInitialized = false

    /**
     * Initialize the Mintegral SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Mintegral.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Map<String, Any>> {
        PartnerLogController.log(SETUP_STARTED)

        val appId =
            Json.decodeFromJsonElement<String>(
                partnerConfiguration.credentials.getValue(APP_ID_KEY),
            ).trim()
        val appKey =
            Json.decodeFromJsonElement<String>(
                partnerConfiguration.credentials.getValue(APP_KEY_KEY),
            ).trim()

        if (!canInitialize(appId, appKey)) {
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials))
        }

        return suspendCancellableCoroutine { continuation ->
            MBridgeSDKFactory.getMBridgeSDK().apply {
                init(
                    getMBConfigurationMap(appId, appKey),
                    context,
                    object : SDKInitStatusListener {
                        fun resumeOnce(result: Result<Map<String, Any>>) {
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }

                        override fun onInitSuccess() {
                            isSdkInitialized = true
                            PartnerLogController.log(SETUP_SUCCEEDED)
                            resumeOnce(Result.success(emptyMap()))
                        }

                        override fun onInitFail(error: String?) {
                            PartnerLogController.log(SETUP_FAILED, "$error")
                            resumeOnce(
                                Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown)),
                            )
                        }
                    },
                )
            }
        }
    }

    /**
     * Notify Mintegral of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is subject to COPPA, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        if (!isSdkInitialized) {
            return
        }
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        MBridgeSDKFactory.getMBridgeSDK()?.setDoNotTrackStatus(isUserUnderage)
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        val token = BidManager.getBuyerUid(context) ?: ""

        PartnerLogController.log(if (token.isNotEmpty()) BIDDER_INFO_FETCH_SUCCEEDED else BIDDER_INFO_FETCH_FAILED)
        return Result.success(mapOf("buyeruid" to token))
    }

    /**
     * Attempt to load a Mintegral ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        // Programmatic and non-programmatic bid responses for Mintegral currently employ two different
        // key names for the Mintegral unit ID.
        val unitId = request.partnerSettings["mintegral_unit_id"] as? String ?: request.partnerSettings["unit_id"] as? String ?: ""

        if (!canLoadAd(context, request.partnerPlacement, unitId)) {
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidPartnerPlacement))
        }

        return when (request.format) {
            PartnerAdFormats.BANNER -> loadBannerAd(context, request, unitId, partnerAdListener)
            PartnerAdFormats.INTERSTITIAL -> loadInterstitialAd(context, request, unitId, partnerAdListener)
            PartnerAdFormats.REWARDED -> loadRewardedAd(context, request, unitId, partnerAdListener)
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to show the currently loaded Mintegral ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return partnerAd.ad?.let {
            return suspendCancellableCoroutine { continuation ->
                val continuationWeakRef = WeakReference(continuation)

                fun resumeOnce(result: Result<PartnerAd>) {
                    continuationWeakRef.get()?.let {
                        if (it.isActive) {
                            it.resume(result)
                        }
                    }
                }

                var failed = false
                when (it) {
                    is MBInterstitialVideoHandler -> if (it.isReady) it.show() else failed = true
                    is MBBidInterstitialVideoHandler -> if (it.isBidReady) it.showFromBid() else failed = true
                    is MBRewardVideoHandler -> if (it.isReady) it.show() else failed = true
                    is MBBidRewardVideoHandler -> if (it.isBidReady) it.showFromBid() else failed = true
                    is MBBannerView -> resumeOnce(Result.success(partnerAd))
                }

                if (failed) {
                    PartnerLogController.log(SHOW_FAILED)
                    resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady)))
                    return@suspendCancellableCoroutine
                }

                onShowSuccess = {
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    resumeOnce(Result.success(partnerAd))
                }

                onShowFailure = {
                    PartnerLogController.log(SHOW_FAILED)
                    resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.Unknown)))
                }
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotFound))
        }
    }

    /**
     * Discard unnecessary Mintegral ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        // Only invalidating banner ads as there are no fullscreen invalidation APIs.
        return partnerAd.ad?.let {
            if (it is MBBannerView) {
                it.release()
            }

            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
        }
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>,
    ) {
        if (!isSdkInitialized) {
            return
        }

        val consent = consents[configuration.partnerId]?.takeIf { it.isNotBlank() }
            ?: consents[ConsentKeys.GDPR_CONSENT_GIVEN]?.takeIf { it.isNotBlank() }
        consent?.let {
            if (it == ConsentValues.DOES_NOT_APPLY) {
                return@let
            }
            PartnerLogController.log(
                when (it) {
                    ConsentValues.GRANTED -> GDPR_CONSENT_GRANTED
                    ConsentValues.DENIED -> GDPR_CONSENT_DENIED
                    else -> GDPR_CONSENT_UNKNOWN
                },
            )

            MBridgeSDKFactory.getMBridgeSDK()?.setConsentStatus(
                context,
                if (it == ConsentValues.GRANTED) {
                    MBridgeConstans.IS_SWITCH_ON
                } else {
                    MBridgeConstans.IS_SWITCH_OFF
                },
            )
        }

        val hasGrantedUspConsent =
            consents[ConsentKeys.CCPA_OPT_IN]?.takeIf { it.isNotBlank() }
                ?.equals(ConsentValues.GRANTED)
                ?: consents[ConsentKeys.USP]?.takeIf { it.isNotBlank() }
                    ?.let { ConsentManagementPlatform.getUspConsentFromUspString(it) }
        hasGrantedUspConsent?.let {
            PartnerLogController.log(
                if (hasGrantedUspConsent) {
                    USP_CONSENT_GRANTED
                } else {
                    USP_CONSENT_DENIED
                },
            )

            MBridgeSDKFactory.getMBridgeSDK()
                ?.setDoNotTrackStatus(!hasGrantedUspConsent)
        }
    }

    /**
     * Check if the Mintegral SDK can initialize using the provided config.
     *
     * @param appId The Mintegral app ID, if any.
     * @param appKey The Mintegral app key, if any.
     *
     * @return True if the Mintegral SDK can initialize, false otherwise.
     */
    private fun canInitialize(
        appId: String?,
        appKey: String?,
    ): Boolean {
        return when {
            appId.isNullOrEmpty() -> {
                PartnerLogController.log(SETUP_FAILED, "The app ID is null/empty.")
                false
            }
            appKey.isNullOrEmpty() -> {
                PartnerLogController.log(SETUP_FAILED, "The app key is null/empty.")
                false
            }
            else -> true
        }
    }

    /**
     * Check whether ads can be loaded.
     *
     * @param context The current [Context].
     * @param partnerPlacement The placement for the ad.
     * @param partnerUnitId The unit ID for the ad.
     *
     * @return True if ads can be loaded, false otherwise.
     */
    private fun canLoadAd(
        context: Context,
        partnerPlacement: String,
        partnerUnitId: String,
    ): Boolean {
        return when {
            !isSdkInitialized -> {
                PartnerLogController.log(LOAD_FAILED, "The SDK is not initialized.")
                false
            }
            context !is Activity -> {
                PartnerLogController.log(LOAD_FAILED, "Context must be an Activity.")
                false
            }
            partnerPlacement.isEmpty() || partnerUnitId.isEmpty() -> {
                PartnerLogController.log(LOAD_FAILED, "Missing placement or unit ID.")
                false
            }
            else -> true
        }
    }

    /**
     * Attempt to load a Mintegral banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerUnitId: String,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        val adm = request.adm
        val size = getMintegralBannerSize(request.bannerSize?.asSize())

        return suspendCancellableCoroutine { continuation ->
            val ad = MBBannerView(context)
            ad.layoutParams =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )

            ad.init(size, request.partnerPlacement, partnerUnitId)
            ad.setBannerAdListener(
                object : BannerAdListener {
                    fun resumeOnce(result: Result<PartnerAd>) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    override fun onLoadFailed(
                        p0: MBridgeIds?,
                        error: String?,
                    ) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            "Placement: ${request.partnerPlacement}. Error: $error",
                        )
                        resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.Unknown)))
                    }

                    override fun onLoadSuccessed(p0: MBridgeIds?) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(Result.success(PartnerAd(ad = ad, details = emptyMap(), request = request)))
                    }

                    override fun onLogImpression(p0: MBridgeIds?) {
                        PartnerLogController.log(DID_TRACK_IMPRESSION)
                        listener.onPartnerAdImpression(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                        )
                    }

                    override fun onClick(p0: MBridgeIds?) {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                        )
                    }

                    override fun onLeaveApp(p0: MBridgeIds?) {
                    }

                    override fun showFullScreen(p0: MBridgeIds?) {
                    }

                    override fun closeFullScreen(p0: MBridgeIds?) {
                    }

                    override fun onCloseBanner(p0: MBridgeIds?) {
                    }
                },
            )

            if (!adm.isNullOrEmpty()) {
                ad.loadFromBid(adm)
            } else {
                ad.load()
            }
        }
    }

    /**
     * Convert a Chartboost Mediation banner size into the corresponding Mintegral banner size.
     *
     * @param size The Chartboost Mediation banner size.
     *
     * @return The Mintegral banner size.
     */
    private fun getMintegralBannerSize(size: Size?): BannerSize {
        return size?.height?.let {
            when {
                it in 50 until 90 -> BannerSize(BannerSize.STANDARD_TYPE, 320, 50)
                it in 90 until 250 -> BannerSize(BannerSize.DEV_SET_TYPE, 728, 90)
                it >= 250 -> BannerSize(BannerSize.MEDIUM_TYPE, 300, 250)
                else -> BannerSize(BannerSize.STANDARD_TYPE, 320, 50)
            }
        } ?: BannerSize(BannerSize.STANDARD_TYPE, 320, 50)
    }

    /**
     * Attempt to load a Mintegral interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerUnitId: String,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        val adm = request.adm

        return if (!adm.isNullOrEmpty()) {
            loadBiddingInterstitialAd(context, request, adm, partnerUnitId, listener)
        } else {
            loadNonBiddingInterstitialAd(context, request, partnerUnitId, listener)
        }
    }

    /**
     * Attempt to load a bidding Mintegral interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param adm The ad markup.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBiddingInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        adm: String,
        partnerUnitId: String,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val ad = MBBidInterstitialVideoHandler(context, request.partnerPlacement, partnerUnitId)
            ad.playVideoMute(
                if (MintegralAdapterConfiguration.mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE,
            )
            ad.setInterstitialVideoListener(
                InterstitialAdLoadCallback(
                    listener,
                    request,
                    PartnerAd(ad = ad, details = emptyMap(), request = request),
                    WeakReference(continuation),
                ),
            )

            ad.loadFromBid(adm)
        }
    }

    /**
     * Attempt to load a non-bidding Mintegral interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadNonBiddingInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerUnitId: String,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val ad = MBInterstitialVideoHandler(context, request.partnerPlacement, partnerUnitId)
            ad.playVideoMute(
                if (MintegralAdapterConfiguration.mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE,
            )
            ad.setInterstitialVideoListener(
                InterstitialAdLoadCallback(
                    listener,
                    request,
                    PartnerAd(ad = ad, details = emptyMap(), request = request),
                    WeakReference(continuation),
                ),
            )

            ad.load()
        }
    }

    /**
     * Attempt to load a Mintegral rewarded ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerUnitId: String,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        val adm = request.adm

        return if (!adm.isNullOrEmpty()) {
            loadBiddingRewardedAd(context, request, adm, partnerUnitId, listener)
        } else {
            loadNonBiddingRewardedAd(context, request, partnerUnitId, listener)
        }
    }

    /**
     * Attempt to load a bidding Mintegral rewarded ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param adm The ad markup.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBiddingRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        adm: String,
        partnerUnitId: String,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val ad = MBBidRewardVideoHandler(context, request.partnerPlacement, partnerUnitId)
            ad.playVideoMute(
                if (MintegralAdapterConfiguration.mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE,
            )
            ad.setRewardVideoListener(
                RewardedAdLoadCallback(
                    listener,
                    request,
                    PartnerAd(ad = ad, details = emptyMap(), request = request),
                    WeakReference(continuation),
                ),
            )

            ad.loadFromBid(adm)
        }
    }

    /**
     * Attempt to load a non-bidding Mintegral rewarded ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadNonBiddingRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerUnitId: String,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            val ad = MBRewardVideoHandler(context, request.partnerPlacement, partnerUnitId)
            ad.playVideoMute(
                if (MintegralAdapterConfiguration.mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE,
            )
            ad.setRewardVideoListener(
                RewardedAdLoadCallback(
                    listener,
                    request,
                    PartnerAd(ad = ad, details = emptyMap(), request = request),
                    WeakReference(continuation),
                ),
            )

            ad.load()
        }
    }
}

/**
 * Callback implementation for Mintegral interstitial ad events.
 *
 * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
 * @param request A [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
 * @param partnerAd The [PartnerAd] object containing the ad to be shown.
 * @param continuationRef A [WeakReference] to the [CancellableContinuation] for the current ad load call.
 */
private class InterstitialAdLoadCallback(
    private val listener: PartnerAdListener,
    private val request: PartnerAdLoadRequest,
    private val partnerAd: PartnerAd,
    private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
) : InterstitialVideoListener {
    override fun onLoadSuccess(p0: MBridgeIds?) {
    }

    override fun onVideoLoadSuccess(p0: MBridgeIds?) {
        PartnerLogController.log(LOAD_SUCCEEDED)

        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(Result.success(partnerAd))
            }
        } ?: run {
            PartnerLogController.log(CUSTOM, "Unable to resume continuation for onVideoLoadSuccess. Continuation is null.")
        }
    }

    override fun onVideoLoadFail(
        p0: MBridgeIds?,
        error: String?,
    ) {
        PartnerLogController.log(
            LOAD_FAILED,
            "Placement: ${request.partnerPlacement}. Error: $error",
        )

        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.Unknown)))
            }
        } ?: run {
            PartnerLogController.log(CUSTOM, "Unable to resume continuation for onVideoLoadFail. Continuation is null.")
        }
    }

    override fun onAdShow(p0: MBridgeIds?) {
        onShowSuccess()
        onShowSuccess = {}
    }

    override fun onAdClose(
        p0: MBridgeIds?,
        p1: RewardInfo?,
    ) {
        PartnerLogController.log(DID_DISMISS)
        listener.onPartnerAdDismissed(
            partnerAd,
            null,
        )
    }

    override fun onShowFail(
        p0: MBridgeIds?,
        p1: String?,
    ) {
        onShowFailure()
        onShowFailure = {}
    }

    override fun onVideoAdClicked(p0: MBridgeIds?) {
        PartnerLogController.log(DID_CLICK)
        listener.onPartnerAdClicked(
            partnerAd,
        )
    }

    override fun onVideoComplete(p0: MBridgeIds?) {
        PartnerLogController.log(CUSTOM, "onVideoComplete")
    }

    override fun onAdCloseWithIVReward(
        p0: MBridgeIds?,
        p1: RewardInfo?,
    ) {
        PartnerLogController.log(CUSTOM, "onAdCloseWithIVReward")
    }

    override fun onEndcardShow(p0: MBridgeIds?) {
        PartnerLogController.log(CUSTOM, "onEndcardShow")
    }
}

/**
 * Callback implementation for Mintegral rewarded ad events.
 *
 * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
 * @param request A [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
 * @param partnerAd The [PartnerAd] object containing the ad to be shown.
 * @param continuationRef A [WeakReference] to the [CancellableContinuation] for the current ad load call.
 */
private class RewardedAdLoadCallback(
    private val listener: PartnerAdListener,
    private val request: PartnerAdLoadRequest,
    private val partnerAd: PartnerAd,
    private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
) : RewardVideoListener {
    override fun onVideoLoadSuccess(p0: MBridgeIds?) {
        PartnerLogController.log(LOAD_SUCCEEDED)
        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(Result.success(partnerAd))
            }
        } ?: run {
            PartnerLogController.log(CUSTOM, "Unable to resume continuation for onVideoLoadSuccess. Continuation is null.")
        }
    }

    override fun onLoadSuccess(p0: MBridgeIds?) {
    }

    override fun onVideoLoadFail(
        p0: MBridgeIds?,
        error: String?,
    ) {
        PartnerLogController.log(LOAD_FAILED, "$error")

        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.Unknown)))
            }
        } ?: run {
            PartnerLogController.log(CUSTOM, "Unable to resume continuation for onVideoLoadFail. Continuation is null.")
        }
    }

    override fun onAdShow(p0: MBridgeIds?) {
        onShowSuccess()
        onShowSuccess = {}
    }

    override fun onAdClose(
        p0: MBridgeIds?,
        rewardInfo: RewardInfo?,
    ) {
        PartnerLogController.log(DID_REWARD)
        listener.onPartnerAdRewarded(partnerAd)

        PartnerLogController.log(DID_DISMISS)
        listener.onPartnerAdDismissed(partnerAd, null)
    }

    override fun onShowFail(
        p0: MBridgeIds?,
        p1: String?,
    ) {
        onShowFailure()
        onShowFailure = {}
    }

    override fun onVideoAdClicked(p0: MBridgeIds?) {
        PartnerLogController.log(DID_CLICK)
        listener.onPartnerAdClicked(partnerAd)
    }

    override fun onVideoComplete(p0: MBridgeIds?) {
        PartnerLogController.log(CUSTOM, "onVideoComplete")
    }

    override fun onEndcardShow(p0: MBridgeIds?) {
        PartnerLogController.log(CUSTOM, "onEndcardShow")
    }
}
