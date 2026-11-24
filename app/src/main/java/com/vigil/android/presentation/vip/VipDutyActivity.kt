package com.vigil.android.presentation.vip

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vigil.android.presentation.traffic.TrafficDependencies
import com.vigil.android.ui.theme.VigilTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class VipPostDto(val id: Long, val name: String, val starts_at: String, val ends_at: String,
    val active: Boolean, val latitude: Double, val longitude: Double, val radius_m: Int)
internal data class VipFix(val latitude: Double, val longitude: Double, val accuracy_m: Float, val captured_at: String)

internal interface VipApi {
    @GET("api/v1/vip/my-posts/") suspend fun posts(): List<VipPostDto>
    @POST("api/v1/vip/my-posts/{id}/location/") suspend fun send(@Path("id") id: Long, @Body fix: VipFix): retrofit2.Response<okhttp3.ResponseBody>
}

internal class VipClient(ctx: Context) {
    private val deps = EntryPointAccessors.fromApplication(ctx.applicationContext, TrafficDependencies::class.java)
    val api = deps.retrofit().create(VipApi::class.java)
    private val session = deps.sessionManager()
    fun account() = session.getUserName()?.takeIf { session.isSessionValid() }
}

class VipDutyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = VipClient(this)
        setContent {
            VigilTheme {
                var posts by remember { mutableStateOf<List<VipPostDto>>(emptyList()) }
                var loaded by remember { mutableStateOf(false) }
                var problem by remember { mutableStateOf("") }
                var pending by remember { mutableStateOf<VipPostDto?>(null) }
                val sharing by VipLocationService.sharing.collectAsState()
                val account = remember { client.account() }
                fun begin(p: VipPostDto) {
                    if (account == null || account != client.account()) return
                    try {
                        val intent = Intent(this@VipDutyActivity, VipLocationService::class.java)
                            .putExtra("post", p.id).putExtra("owner", account)
                            .putExtra("end", OffsetDateTime.parse(p.ends_at).toInstant().toEpochMilli())
                        ContextCompat.startForegroundService(this@VipDutyActivity, intent)
                    } catch (_: Exception) { problem = "Location sharing could not start. Check permissions and reopen this screen." }
                }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                    val requested = pending
                    pending = null
                    if (requested != null && granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) begin(requested)
                    else problem = "Precise location is required to monitor your duty circle."
                }
                LaunchedEffect(account) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (isActive) {
                            if (client.account() != account || account == null) { finish(); break }
                            try { posts = client.api.posts(); loaded = true; problem = "" }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { problem = "Could not refresh assignments. Check your connection." }
                            delay(15_000)
                        }
                    }
                }
                Surface(modifier=Modifier.fillMaxSize()) {
                    LazyColumn(modifier=Modifier.systemBarsPadding().padding(18.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item {
                            Text("VIP duty", style=MaterialTheme.typography.headlineMedium)
                            Text("Assigned posts and duty location sharing", style=MaterialTheme.typography.bodyMedium)
                        }
                        if (problem.isNotBlank()) item { Text(problem, color=MaterialTheme.colorScheme.error) }
                        if (posts.isEmpty()) item { Text(if (loaded) "No current or upcoming posts." else "Loading posts…") }
                        items(posts,key={it.id}) { p ->
                            Card(modifier=Modifier.fillMaxWidth()) {
                                Column(modifier=Modifier.padding(15.dp), verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                    Text(p.name,style=MaterialTheme.typography.titleMedium)
                                    Text("${time(p.starts_at)} – ${time(p.ends_at)}")
                                    Text("Duty centre  ${p.latitude}, ${p.longitude} · ${p.radius_m} m")
                                    if (sharing == p.id) {
                                        Text("Your duty location is being shared.")
                                        OutlinedButton(onClick={stopService(Intent(this@VipDutyActivity, VipLocationService::class.java))}) { Text("Stop sharing") }
                                    } else if (p.active) {
                                        Button(onClick={
                                            if (ContextCompat.checkSelfPermission(this@VipDutyActivity, Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) begin(p)
                                            else { pending=p;permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)) }
                                        }) { Text("Share duty location") }
                                    } else Text("Scheduled · waiting for control room to start")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun time(value:String) = OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Kolkata"))
        .format(DateTimeFormatter.ofPattern("dd MMM, HH:mm 'IST'"))
}
