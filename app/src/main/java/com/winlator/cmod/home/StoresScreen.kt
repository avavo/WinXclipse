package com.winlator.cmod.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R

private val StoreShape = RoundedCornerShape(18.dp)

/** Stores hub: Steam and GOG, Epic next. */
@Composable
fun StoresScreen(
    steamAccount: String?,
    onBack: () -> Unit,
    onConnectSteam: () -> Unit,
    onOpenSteamLibrary: () -> Unit,
    onSignOutSteam: () -> Unit,
    gogAccount: String?,
    onConnectGog: () -> Unit,
    onOpenGogLibrary: () -> Unit,
    onSignOutGog: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val steamFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { steamFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Wx.Ground)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 26.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WxIconButton(WxIcons.Back, stringResource(R.string.game_back_home), onBack)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(stringResource(R.string.sto_title), style = WxType.Title.copy(fontSize = 25.sp, fontWeight = FontWeight.ExtraBold))
                    Text(stringResource(R.string.sto_blurb), style = WxType.Caption)
                }
            }
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(top = 18.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StoreCard(
                    name = "Steam",
                    description = stringResource(R.string.sto_steam_desc),
                    status = steamAccount?.let { stringResource(R.string.sto_connected_as, it) } ?: stringResource(R.string.sto_not_connected),
                    brush = Brush.linearGradient(listOf(Color(0xFF1B2838), Color(0xFF2A475E), Color(0xFF66C0F4))),
                    modifier = Modifier.weight(1f),
                ) {
                    AccountActions(steamAccount, onConnectSteam, onOpenSteamLibrary, onSignOutSteam, Modifier.focusRequester(steamFocus))
                }
                StoreCard(
                    name = "GOG",
                    description = stringResource(R.string.sto_gog_desc),
                    status = gogAccount?.let { stringResource(R.string.sto_connected_as, it) } ?: stringResource(R.string.sto_not_connected),
                    brush = Brush.linearGradient(listOf(Color(0xFF3A1B5C), Color(0xFF86328A))),
                    modifier = Modifier.weight(1f),
                ) {
                    AccountActions(gogAccount, onConnectGog, onOpenGogLibrary, onSignOutGog)
                }
                StoreCard(
                    name = "Epic Games",
                    description = stringResource(R.string.sto_epic_desc),
                    status = stringResource(R.string.sto_coming_soon),
                    brush = Brush.linearGradient(listOf(Color(0xFF121212), Color(0xFF3A3A3A))),
                    modifier = Modifier.weight(1f).alpha(0.6f),
                ) { }
            }
        }
    }
}

/** Connect, or open the library and sign out (stacked: side by side they do not fit a third of a phone screen). */
@Composable
private fun AccountActions(
    account: String?,
    onConnect: () -> Unit,
    onOpenLibrary: () -> Unit,
    onSignOut: () -> Unit,
    focus: Modifier = Modifier,
) {
    if (account == null) {
        WxPrimaryButton(stringResource(R.string.sto_connect), onConnect, focus, height = 42.dp)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WxPrimaryButton(
                stringResource(R.string.sto_open_library),
                onOpenLibrary,
                Modifier.fillMaxWidth().then(focus),
                height = 42.dp,
                horizontalPadding = 16.dp,
            )
            WxTonalButton(stringResource(R.string.sto_sign_out), onSignOut, Modifier.fillMaxWidth(), height = 38.dp)
        }
    }
}

@Composable
private fun StoreCard(
    name: String,
    description: String,
    status: String,
    brush: Brush,
    modifier: Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(StoreShape)
            .background(Wx.Surface)
            .border(1.dp, Wx.TintBorder.copy(alpha = 0.25f), StoreShape),
    ) {
        Box(Modifier.fillMaxWidth().height(110.dp).background(brush), contentAlignment = Alignment.BottomStart) {
            Text(
                name,
                style = WxType.Display.copy(fontSize = 30.sp, color = Color.White),
                modifier = Modifier.padding(16.dp),
            )
        }
        Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(description, style = WxType.Body.copy(color = Wx.TextSecondary))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Wx.Lilac))
                Text(status, style = WxType.Caption.copy(color = Wx.Lilac), modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
    }
}
