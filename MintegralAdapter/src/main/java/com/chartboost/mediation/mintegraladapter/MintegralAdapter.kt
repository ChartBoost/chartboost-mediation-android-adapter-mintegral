/*
 * Copyright 2022-2023 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.mintegraladapter

import android.app.Activity
import android.content.Context
import android.util.Size
import android.widget.FrameLayout
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.mbridge.msdk.MBridgeConstans
import com.mbridge.msdk.interstitialvideo.out.InterstitialVideoListener
import com.mbridge.msdk.interstitialvideo.out.MBBidInterstitialVideoHandler
import com.mbridge.msdk.interstitialvideo.out.MBInterstitialVideoHandler
import com.mbridge.msdk.mbbid.out.BidManager
import com.mbridge.msdk.out.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation Mintegral Adapter.
 */
class MintegralAdapter : PartnerAdapter {
    companion object {
        /**
         * Flag that can optionally be set to mute video creatives served by Mintegral. This can be
         * set at any time and will take effect for the next ad request.
         *
         * https://dev.mintegral.com/doc/index.html?file=sdk-m_sdk-android&lang=en
         */
        var mute = false
            set(value) {
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "Mintegral video creatives will be ${if (value) "muted" else "unmuted"}.",
                )
            }

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
     * Lambda to be called for a successful Mintegral ad show.
     */
    private var onShowSuccess: () -> Unit = {}

    /**
     * Lambda to be called for a failed Mintegral ad show.
     */
    private var onShowFailure: () -> Unit = {}

    /**
     * Track whether the Mintegral SDK has been successfully initialized.
     */
    private var isSdkInitialized = false

    /**
     * Get the Mintegral SDK version.
     */
    override val partnerSdkVersion: String
        get() = MBConfiguration.SDK_VERSION

    /**
     * Get the Mintegral adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_MINTEGRAL_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "mintegral"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "Mintegral"

    /**
     * Initialize the Mintegral SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Mintegral.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Unit> {
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
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS))
        }

        return suspendCancellableCoroutine { continuation ->
            MBridgeSDKFactory.getMBridgeSDK().apply {
                init(
                    getMBConfigurationMap(appId, appKey),
                    context,
                    object : SDKInitStatusListener {
                        fun resumeOnce(result: Result<Unit>) {
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }

                        override fun onInitSuccess() {
                            isSdkInitialized = true
                            resumeOnce(Result.success(PartnerLogController.log(SETUP_SUCCEEDED)))
                        }

                        override fun onInitFail(error: String?) {
                            PartnerLogController.log(SETUP_FAILED, "$error")
                            resumeOnce(
                                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN)),
                            )
                        }
                    },
                )
            }
        }
    }

    /**
     * Notify the Mintegral SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus,
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            },
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            },
        )

        if (applies == true && isSdkInitialized) {
            MBridgeSDKFactory.getMBridgeSDK()?.setConsentStatus(
                context,
                if (gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED) {
                    MBridgeConstans.IS_SWITCH_ON
                } else {
                    MBridgeConstans.IS_SWITCH_OFF
                },
            )
        }
    }

    /**
     * Notify Mintegral of the user's CCPA consent status, if applicable.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String,
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) {
                CCPA_CONSENT_GRANTED
            } else {
                CCPA_CONSENT_DENIED
            },
        )

        if (isSdkInitialized) {
            MBridgeSDKFactory.getMBridgeSDK()
                ?.setDoNotTrackStatus(!hasGrantedCcpaConsent)
        }
    }

    /**
     * Notify Mintegral of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(
        context: Context,
        isSubjectToCoppa: Boolean,
    ) {
        PartnerLogController.log(
            if (isSubjectToCoppa) {
                COPPA_SUBJECT
            } else {
                COPPA_NOT_SUBJECT
            },
        )

        if (isSdkInitialized) {
            MBridgeSDKFactory.getMBridgeSDK()
                ?.setDoNotTrackStatus(isSubjectToCoppa)
        }
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest,
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        val token = BidManager.getBuyerUid(context) ?: ""

        PartnerLogController.log(if (token.isNotEmpty()) BIDDER_INFO_FETCH_SUCCEEDED else BIDDER_INFO_FETCH_FAILED)
        return mapOf("buyeruid" to token)
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
        val unitId = request.partnerSettings["mintegral_unit_id"] ?: request.partnerSettings["unit_id"] ?: ""

        if (!canLoadAd(context, request.partnerPlacement, unitId)) {
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_INVALID_PARTNER_PLACEMENT))
        }

        return when (request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> loadBannerAd(context, request, unitId, partnerAdListener)
            AdFormat.INTERSTITIAL.key -> loadInterstitialAd(context, request, unitId, partnerAdListener)
            AdFormat.REWARDED.key -> loadRewardedAd(context, request, unitId, partnerAdListener)
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Attempt to show the currently loaded Mintegral ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        context: Context,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return partnerAd.ad?.let {
            return suspendCancellableCoroutine { continuation ->
                fun resumeOnce(result: Result<PartnerAd>) {
                    if (continuation.isActive) {
                        continuation.resume(result)
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
                    resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY)))
                    return@suspendCancellableCoroutine
                }

                onShowSuccess = {
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    resumeOnce(Result.success(partnerAd))
                }

                onShowFailure = {
                    PartnerLogController.log(SHOW_FAILED)
                    resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNKNOWN)))
                }
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
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
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
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
        val size = getMintegralBannerSize(request.size)

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
                        resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN)))
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
            ad.playVideoMute(if (mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE)
            ad.setInterstitialVideoListener(
                object : InterstitialVideoListener {
                    fun resumeOnce(result: Result<PartnerAd>) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    override fun onLoadSuccess(p0: MBridgeIds?) {
                    }

                    override fun onVideoLoadSuccess(p0: MBridgeIds?) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(Result.success(PartnerAd(ad = ad, details = emptyMap(), request = request)))
                    }

                    override fun onVideoLoadFail(
                        p0: MBridgeIds?,
                        error: String?,
                    ) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            "Placement: ${request.partnerPlacement}. Error: $error",
                        )
                        resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN)))
                    }

                    override fun onAdShow(p0: MBridgeIds?) {
                        onShowSuccess()
                    }

                    override fun onAdClose(
                        p0: MBridgeIds?,
                        p1: RewardInfo?,
                    ) {
                        PartnerLogController.log(DID_DISMISS)
                        listener.onPartnerAdDismissed(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                            null,
                        )
                    }

                    override fun onShowFail(
                        p0: MBridgeIds?,
                        p1: String?,
                    ) {
                        onShowFailure()
                    }

                    override fun onVideoAdClicked(p0: MBridgeIds?) {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                        )
                    }

                    override fun onVideoComplete(p0: MBridgeIds?) {
                    }

                    override fun onAdCloseWithIVReward(
                        p0: MBridgeIds?,
                        p1: RewardInfo?,
                    ) {
                    }

                    override fun onEndcardShow(p0: MBridgeIds?) {
                    }
                },
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
            ad.playVideoMute(if (mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE)
            ad.setInterstitialVideoListener(
                object : InterstitialVideoListener {
                    fun resumeOnce(result: Result<PartnerAd>) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    override fun onLoadSuccess(p0: MBridgeIds?) {
                    }

                    override fun onVideoLoadSuccess(p0: MBridgeIds?) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(Result.success(PartnerAd(ad = ad, details = emptyMap(), request = request)))
                    }

                    override fun onVideoLoadFail(
                        p0: MBridgeIds?,
                        error: String?,
                    ) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            "Placement: ${request.partnerPlacement}. Error: $error",
                        )
                        resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN)))
                    }

                    override fun onAdShow(p0: MBridgeIds?) {
                        onShowSuccess()
                    }

                    override fun onAdClose(
                        p0: MBridgeIds?,
                        p1: RewardInfo?,
                    ) {
                        PartnerLogController.log(DID_DISMISS)
                        listener.onPartnerAdDismissed(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                            null,
                        )
                    }

                    override fun onShowFail(
                        p0: MBridgeIds?,
                        p1: String?,
                    ) {
                        onShowFailure()
                    }

                    override fun onVideoAdClicked(p0: MBridgeIds?) {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                        )
                    }

                    override fun onVideoComplete(p0: MBridgeIds?) {
                    }

                    override fun onAdCloseWithIVReward(
                        p0: MBridgeIds?,
                        p1: RewardInfo?,
                    ) {
                    }

                    override fun onEndcardShow(p0: MBridgeIds?) {
                    }
                },
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
            ad.playVideoMute(if (mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE)
            ad.setRewardVideoListener(
                object : RewardVideoListener {
                    fun resumeOnce(result: Result<PartnerAd>) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    override fun onVideoLoadSuccess(p0: MBridgeIds?) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(Result.success(PartnerAd(ad = ad, details = emptyMap(), request = request)))
                    }

                    override fun onLoadSuccess(p0: MBridgeIds?) {
                    }

                    override fun onVideoLoadFail(
                        p0: MBridgeIds?,
                        error: String?,
                    ) {
                        PartnerLogController.log(LOAD_FAILED, "$error")
                        resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN)))
                    }

                    override fun onAdShow(p0: MBridgeIds?) {
                        onShowSuccess()
                    }

                    override fun onAdClose(
                        p0: MBridgeIds?,
                        rewardInfo: RewardInfo?,
                    ) {
                        PartnerLogController.log(DID_REWARD)
                        listener.onPartnerAdRewarded(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                        )
                        PartnerLogController.log(DID_DISMISS)
                        listener.onPartnerAdDismissed(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                            null,
                        )
                    }

                    override fun onShowFail(
                        p0: MBridgeIds?,
                        p1: String?,
                    ) {
                        onShowFailure()
                    }

                    override fun onVideoAdClicked(p0: MBridgeIds?) {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                        )
                    }

                    override fun onVideoComplete(p0: MBridgeIds?) {
                    }

                    override fun onEndcardShow(p0: MBridgeIds?) {
                    }
                },
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
            ad.playVideoMute(if (mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE)
            ad.setRewardVideoListener(
                object : RewardVideoListener {
                    fun resumeOnce(result: Result<PartnerAd>) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    override fun onVideoLoadSuccess(p0: MBridgeIds?) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(Result.success(PartnerAd(ad = ad, details = emptyMap(), request = request)))
                    }

                    override fun onLoadSuccess(p0: MBridgeIds?) {
                    }

                    override fun onVideoLoadFail(
                        p0: MBridgeIds?,
                        error: String?,
                    ) {
                        PartnerLogController.log(LOAD_FAILED, "$error")
                        resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN)))
                    }

                    override fun onAdShow(p0: MBridgeIds?) {
                        onShowSuccess()
                    }

                    override fun onAdClose(
                        p0: MBridgeIds?,
                        rewardInfo: RewardInfo?,
                    ) {
                        PartnerLogController.log(DID_REWARD)
                        listener.onPartnerAdRewarded(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                        )
                        PartnerLogController.log(DID_DISMISS)
                        listener.onPartnerAdDismissed(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                            null,
                        )
                    }

                    override fun onShowFail(
                        p0: MBridgeIds?,
                        p1: String?,
                    ) {
                        onShowFailure()
                    }

                    override fun onVideoAdClicked(p0: MBridgeIds?) {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(
                            PartnerAd(ad = ad, details = emptyMap(), request = request),
                        )
                    }

                    override fun onVideoComplete(p0: MBridgeIds?) {
                    }

                    override fun onEndcardShow(p0: MBridgeIds?) {
                    }
                },
            )

            ad.load()
        }
    }
}
