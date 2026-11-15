package dev.vibex.companion.app.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import dev.vibex.companion.app.ui.theme.VibexTheme

/**
 * 1:1 port of the updated iOS squircle QRScannerView:
 * Frosted card container + centered 248dp squircle live camera preview + caption + quick controls.
 */
@Composable
fun QrScannerOverlay(
    onCode: (String) -> Unit,
    onManualInput: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = VibexTheme.colors
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    var torchOn by remember { mutableStateOf(false) }
    var handled by remember { mutableStateOf(false) }
    var barcodeViewRef by remember { mutableStateOf<DecoratedBarcodeView?>(null) }

    BackHandler(onBack = onDismiss)

    DisposableEffect(lifecycleOwner, barcodeViewRef) {
        val view = barcodeViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.resume()
                Lifecycle.Event.ON_PAUSE -> view?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            view?.resume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.pause()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = if (colors.isDark) {
                        listOf(Color(0xFF101014), Color(0xFF18181E))
                    } else {
                        listOf(Color(0xFFF2F2F7), Color(0xFFE5E6EC))
                    },
                ),
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top Close Button
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.card.copy(alpha = 0.9f))
                .border(0.75.dp, colors.stroke, CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭",
                tint = colors.textPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        // Centered Frosted Card with Squircle Viewfinder
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = Color.Black.copy(alpha = 0.10f),
                )
                .clip(RoundedCornerShape(28.dp))
                .background(colors.card.copy(alpha = if (colors.isDark) 0.88f else 0.92f))
                .border(0.75.dp, colors.stroke, RoundedCornerShape(28.dp))
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            val squircleShape = RoundedCornerShape(44.dp)
            Box(
                modifier = Modifier
                    .size(248.dp)
                    .shadow(14.dp, squircleShape, ambientColor = Color.Black.copy(alpha = 0.18f))
                    .clip(squircleShape)
                    .background(Color.Black)
                    .border(
                        width = 1.8.dp,
                        color = colors.textPrimary.copy(alpha = 0.82f),
                        shape = squircleShape,
                    ),
            ) {
                AndroidView(
                    factory = { ctx ->
                        DecoratedBarcodeView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            statusView.visibility = android.view.View.GONE
                            viewFinder.visibility = android.view.View.GONE
                            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                            decodeContinuous { result ->
                                val text = result?.text?.trim().orEmpty()
                                if (text.isNotEmpty() && !handled) {
                                    handled = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCode(text)
                                }
                            }
                            barcodeViewRef = this
                        }
                    },
                    update = { view ->
                        if (torchOn) view.setTorchOn() else view.setTorchOff()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Text(
                text = "请扫描桌面端「远程连接」中的配对二维码",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        // Bottom Controls (Manual Pairing + Torch)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(colors.card.copy(alpha = 0.94f))
                    .border(0.75.dp, colors.stroke, CircleShape)
                    .clickable(onClick = onManualInput)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Keyboard,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = "使用连接码配对",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (torchOn) Color(0xFFFFD60A) else colors.card.copy(alpha = 0.94f))
                    .border(0.75.dp, colors.stroke, CircleShape)
                    .clickable { torchOn = !torchOn },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (torchOn) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                    contentDescription = "手电筒",
                    tint = if (torchOn) Color.Black else colors.textPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
