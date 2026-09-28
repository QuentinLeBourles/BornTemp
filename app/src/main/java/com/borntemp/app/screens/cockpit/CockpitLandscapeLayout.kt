package com.borntemp.app.screens.cockpit

import com.borntemp.app.components.rememberNowMs
import com.borntemp.app.viewmodel.AcquisitionSetting
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.borntemp.app.components.ChargeProjectionCard
import com.borntemp.app.components.CockpitHero
import com.borntemp.app.components.CockpitTab
import com.borntemp.app.components.CockpitTabBar
import com.borntemp.app.components.StatusBanner
import com.borntemp.app.ui.theme.AmberHi
import com.borntemp.app.ui.theme.BornBorder
import com.borntemp.app.ui.theme.RedHi
import com.borntemp.app.ui.theme.Spacing
import com.borntemp.app.viewmodel.PackTypeOverride
import com.borntemp.app.viewmodel.UiState

/**
 * Wide-screen (tablet / landscape ≥600dp) cockpit frame: a persistent left
 * pane (header, banners, hero, charge/thermal projection, action bar) that
 * never changes with the selected tab, and a right pane holding the tab bar
 * (top-pinned — bottom placement stops making sense once tabs aren't at the
 * bottom of a single vertical frame) plus the selected tab's scrollable
 * content. The hero and charge-projection card are only ever rendered here,
 * never inside [CockpitLiveTab] itself, so nothing duplicates on screen.
 */
@Composable
fun CockpitLandscapeLayout(
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
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight()
                .padding(start = Spacing.xl, top = Spacing.lg, bottom = Spacing.lg, end = Spacing.lg)
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

            ChargeProjectionCard(
                projection = uiState.chargeProjection,
                trajectory = uiState.thermalTrajectory,
                modifier = Modifier.padding(bottom = Spacing.md)
            )

            Spacer(Modifier.weight(1f))

            ActionBar(
                connected = connected,
                connecting = connecting,
                onPrimary = onConnectToggle,
                onRefresh = onRefresh
            )
        }

        Box(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(BornBorder)
        )

        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight()
                .padding(start = Spacing.lg, top = Spacing.lg, bottom = Spacing.lg, end = Spacing.xl)
        ) {
            CockpitTabBar(
                selected = selectedTab,
                onSelect = onSelectTab,
                modifier = Modifier.padding(bottom = Spacing.md)
            )
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    CockpitTab.LIVE -> CockpitLiveTab(
                        uiState = uiState,
                        onOpenEstimator = onOpenEstimator,
                        showChargeProjection = false
                    )
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
        }
    }
}
