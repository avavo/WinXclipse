package com.winlator.cmod.home

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.winlator.cmod.R
import com.winlator.cmod.steam.SteamLogin
import com.winlator.cmod.steam.SteamLoginState

/**
 * Steam sign-in: QR code for the Steam mobile app on the left, account name + password
 * (then a Steam Guard code if asked) on the right.
 */
@Composable
fun SteamLoginScreen(login: SteamLogin, onBack: () -> Unit, onSignedIn: (String) -> Unit) {
    BackHandler {
        login.cancel()
        onBack()
    }
    val state by login.state.collectAsState()
    LaunchedEffect(Unit) { login.startQr() }
    LaunchedEffect(state) {
        (state as? SteamLoginState.SignedIn)?.let { onSignedIn(it.accountName) }
    }

    Box(Modifier.fillMaxSize().background(Wx.Ground)) {
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 26.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WxIconButton(WxIcons.Back, stringResource(R.string.game_back_home), {
                        login.cancel()
                        onBack()
                    })
                    Text(
                        stringResource(R.string.steam_login_title),
                        style = WxType.Title.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    QrPanel(state)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.steam_qr_title), style = WxType.Heading.copy(fontSize = 18.sp))
                        Text(stringResource(R.string.steam_qr_steps), style = WxType.Body.copy(color = Wx.TextSecondary))
                        Text(stringResource(R.string.steam_qr_refresh), style = WxType.Caption)
                    }
                }
                Text(stringResource(R.string.steam_privacy), style = WxType.Caption.copy(fontSize = 11.sp))
            }
            Column(
                Modifier
                    .width(330.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CredentialsPanel(state, login)
            }
        }
    }
}

@Composable
private fun QrPanel(state: SteamLoginState) {
    val url = (state as? SteamLoginState.Qr)?.challengeUrl
    val qr = remember(url) { url?.let { renderQr(it, 512) } }
    Box(
        Modifier
            .size(190.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .border(3.dp, Wx.Gradient, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (qr != null) {
            Image(qr, contentDescription = null, modifier = Modifier.padding(12.dp).fillMaxSize())
        } else {
            CircularProgressIndicator(color = Wx.Violet)
        }
    }
}

@Composable
private fun CredentialsPanel(state: SteamLoginState, login: SteamLogin) {
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    when (state) {
        is SteamLoginState.NeedCode -> {
            Text(
                state.email?.let { stringResource(R.string.steam_code_email, it) } ?: stringResource(R.string.steam_code_device),
                style = WxType.Label,
            )
            if (state.wrongCode) Text(stringResource(R.string.steam_code_wrong), style = WxType.Caption.copy(color = Wx.Danger))
            SteamField(code, stringResource(R.string.steam_code), keyboard = KeyboardType.Ascii) { code = it.trim().uppercase() }
            WxPrimaryButton(stringResource(R.string.steam_sign_in), { login.submitCode(code) }, Modifier.fillMaxWidth(), height = 44.dp)
        }
        SteamLoginState.AwaitingAppConfirmation -> StatusLine(stringResource(R.string.steam_confirm_in_app))
        SteamLoginState.Working -> StatusLine(stringResource(R.string.steam_working))
        else -> {
            Text(stringResource(R.string.steam_or_password), style = WxType.Heading)
            SteamField(account, stringResource(R.string.steam_account_name)) { account = it.trim() }
            SteamField(password, stringResource(R.string.steam_password), secret = true, keyboard = KeyboardType.Password) { password = it }
            WxPrimaryButton(
                stringResource(R.string.steam_sign_in),
                { if (account.isNotEmpty() && password.isNotEmpty()) login.signInWithPassword(account, password) },
                Modifier.fillMaxWidth(),
                height = 44.dp,
            )
            if (state is SteamLoginState.Connecting) StatusLine(stringResource(R.string.steam_connecting))
            if (state is SteamLoginState.Failed) {
                Text(state.message, style = WxType.Caption.copy(color = Wx.Danger))
                WxTonalButton(stringResource(R.string.steam_try_again), { login.startQr() }, height = 40.dp)
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = Wx.Violet, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Text(text, style = WxType.Body, modifier = Modifier.padding(start = 10.dp))
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SteamField(
    value: String,
    label: String,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label, style = WxType.Caption) },
        textStyle = WxType.Body.copy(textAlign = TextAlign.Start),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Wx.Violet,
            unfocusedBorderColor = Wx.TintBorder,
            focusedContainerColor = Wx.Tint,
            unfocusedContainerColor = Wx.Surface,
            cursorColor = Wx.Lilac,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Black-on-white QR bitmap for a Steam challenge URL. */
private fun renderQr(text: String, size: Int): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
