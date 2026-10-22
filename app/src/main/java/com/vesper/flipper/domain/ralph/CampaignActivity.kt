package com.vesper.flipper.domain.ralph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Explicit opt-in and durable approval inbox; never launched automatically. */
class CampaignActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme {
            val context = this@CampaignActivity
            val store = remember { CampaignStore(context) }
            val orchestrator = remember { RalphOrchestrator(context) }
            val scope = rememberCoroutineScope()
            var enabled by remember { mutableStateOf(store.enabled) }
            var rows by remember { mutableStateOf(store.rows()) }
            var error by remember { mutableStateOf("") }
            var planText by remember { mutableStateOf(Json { prettyPrint=true }.encodeToString(CampaignPlan(
                title="Scoped inventory",scope="/ext/subghz",expiresAt=System.currentTimeMillis()+3600000,
                phases=listOf(CampaignPhase("Recon",listOf(ExecuteCommand(CommandAction.GET_DEVICE_INFO,CommandArgs(),"Inventory device","Read device information"))),
                    CampaignPhase("Inventory",listOf(ExecuteCommand(CommandAction.LIST_DIRECTORY,CommandArgs(path="/ext/subghz"),"Inventory authorized directory","List captures"))))))) }
            DisposableEffect(Unit) { onDispose { store.close() } }
            LaunchedEffect(Unit) { while(true) { rows=store.rows(); enabled=store.enabled; delay(1000) } }
            LazyColumn(modifier=Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { Text("Experimental campaigns",style=MaterialTheme.typography.headlineSmall) }
                item { Text("Disabled by default. Use only authorized /ext scope. Each write needs the existing approval flow. Interrupted commands are never replayed automatically.") }
                item { Row { Text("Enable campaigns",modifier=Modifier.weight(1f)); Switch(checked=enabled,onCheckedChange={ value -> scope.launch { try { if(value) store.enabled=true else orchestrator.kill(); enabled=value } catch(e:Exception) { error=e.message.orEmpty() } } }) } }
                item { Button(onClick={ scope.launch { orchestrator.kill(); enabled=false } }) { Text("STOP ALL AND DISABLE") } }
                item { Text("Stop prevents later steps. A command already claimed or sent may still finish.") }
                item { OutlinedTextField(value=planText,onValueChange={ if(it.length<=65536) planText=it },label={Text("Campaign plan JSON")},modifier=Modifier.fillMaxWidth(),minLines=5,maxLines=12) }
                item { Button(enabled=enabled,onClick={ try { orchestrator.start(Json.decodeFromString(planText)); error="" } catch(e:Exception) {error=e.message.orEmpty()} }) {Text("Start scoped campaign")} }
                item { if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error) }
                items(rows,key={it.id}) { row -> Card { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(row.plan.title,style=MaterialTheme.typography.titleMedium)
                    Text("${row.state} · ${row.plan.scope} · phase ${row.phase+1} step ${row.step+1}")
                    Text(row.message)
                    if(row.state in setOf("executing","approving")) Text("If this remains after restart, verify the device manually and cancel; commands are not replayed.")
                    if(row.state=="approval") {
                        var confirmation by remember(row.id, row.step) { mutableStateOf("") }
                        Text(Json.encodeToString(row.plan.phases[row.phase].commands[row.step]))
                        Text("Approval expires: ${java.util.Date(row.approvalExpires)}")
                        OutlinedTextField(value=confirmation,onValueChange={confirmation=it},label={Text("Type APPROVE to confirm")})
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=enabled && confirmation == "APPROVE" && System.currentTimeMillis()<row.approvalExpires,onClick={scope.launch {try {orchestrator.approve(row.id)} catch(e:Exception) {error=e.message.orEmpty()}}}) {Text("Approve exact command")}
                            OutlinedButton(onClick={scope.launch {orchestrator.reject(row.id)}}) {Text("Reject")}
                        }
                    } else if(row.state !in setOf("completed","cancelled","failed")) OutlinedButton(onClick={scope.launch {orchestrator.reject(row.id)}}) {Text("Cancel")}
                } } }
            }
        } }
    }
}
