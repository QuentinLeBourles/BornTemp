package com.borntemp.app.screens.cockpit

import com.borntemp.app.components.rememberNowMs
import com.borntemp.app.viewmodel.AcquisitionSetting
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.borntemp.app.components.CockpitHero
import com.borntemp.app.components.CockpitTab
import com.borntemp.app.components.CockpitTabBar
import com.borntemp.app.components.StatusBanner
import com.borntemp.app.ui.theme.AmberHi
import com.borntemp.app.ui.theme.RedHi
import com.borntemp.app.ui.theme.Spacing
import com.borntemp.app.viewmodel.PackTypeOverride
import com.borntemp.app.viewmodel.UiState

/**
 * The default (phone, portrait) cockpit frame: fixed header/banners/hero at
 * top, the selected tab's scrollable content in the middle, and fixed
 * action bar + tab selector at the bottom. The always-visible header/error
 * banner is a real fix over the old single-scroll layout — it can no longer
 * be scrolled out of view.
 */
@Composable
fun CockpitPortraitLayout(
    uiState: UiState,
    selectedTab: CockpitTab,
    onSelectTab: (CockpitTab) -> Unit,
    autoConnectBanner: String?,
    connected: Boolean,
    connecting: Boolean,
    onOpenEstimator: () -> Unit,
    onOpenTrend: () -> Unit,
    onOpenErrorDetail: () -> Unit,
    onPollingIntervalChange: (Long) -> Unit,
    onAcquisitionSettingChange: (AcquisitionSetting) -> Unit,
    onAbrpEnabledChange: (Boolean) -> Unit,
    onAbrpApiKeyChange: (String) -> Unit,
    onAbrpUserTokenChange: (String) -> Unit,
    onPackTypeOverrideChange: (PackTypeOverride) -> Unit,
    onConnectToggle: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg)
    ) {
        autoConnectBanner?.let { message ->
            StatusBanner(
                message = message,
                tint = AmberHi,
                modifier = Modifier.padding(bottom = Spacing.md)
            )
        }

        CockpitHeader(
            connectionState = uiState.connectionState,
            modifier = Modifier.padding(bottom = Spacing.md)
        )

        uiState.errorMessage?.let { err ->
            StatusBanner(
                message = err,
                tint = RedHi,
                onClick = onOpenErrorDetail,
                modifier = Modifier.padding(bottom = Spacing.md)
            )
        }

        val hero = heroSnapshot(

            uiState.connectionState, uiState.batteryData, uiState.offline.lastKnown, rememberNowMs()

        )

        CockpitHero(

            tempAvg = hero.tempAvg,

            tempSlopeCPerMin = uiState.thermalTrajectory.slopeCPerMin,

            socHmi = hero.socHmi,

            sohPct = hero.sohPct,

            volt12v = hero.volt12v,

            modifier = Modifier.padding(bottom = Spacing.md),

            staleLabel = hero.staleLabel,

        )

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                CockpitTab.LIVE -> CockpitLiveTab(uiState = uiState, onOpenEstimator = onOpenEstimator)
                CockpitTab.SANTE -> CockpitHealthTab(uiState = uiState, onOpenTrend = onOpenTrend)
                CockpitTab.HISTO -> CockpitHistoryTab(uiState = uiState)
                CockpitTab.REGLAGES -> CockpitReglagesTab(
                    uiState = uiState,
                    onPollingIntervalChange = onPollingIntervalChange,
                    onAcquisitionSettingChange = onAcquisitionSettingChange,
                    onAbrpEnabledChange = onAbrpEnabledChange,
                    onAbrpApiKeyChange = onAbrpApiKeyChange,
                    onAbrpUserTokenChange = onAbrpUserTokenChange,
                    onPackTypeOverrideChange = onPackTypeOverrideChange
                )
            }
        }

        ActionBar(
            connected = connected,
            connecting = connecting,
            onPrimary = onConnectToggle,
            onRefresh = onRefresh,
            modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.md)
        )

        CockpitTabBar(selected = selectedTab, onSelect = onSelectTab)
    }
}
