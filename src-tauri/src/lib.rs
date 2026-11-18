// Android/Tauri is a thin host. No SQL, storage, model, shell or filesystem commands.
// External reads happen in the page through the canonical consent broker, and
// patient data (SOAP notes, claim lines) stays in page memory: the host never sees it.
#[tauri::command]
fn awai_host_info() -> serde_json::Value {
    serde_json::json!({"schema":"awai.shell.host.v1","app":"network.itonami.karute","inferenceHandoff":false,"external_operations":["itonami-grok-bots/read-assignments","itonami-grok-bots/read-activity"]})
}
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .runtime(tauri_runtime_wry::Wry::default())
        .invoke_handler(tauri::generate_handler![awai_host_info])
        .run(tauri::generate_context!())
        .expect("Karute initialization failed");
}
