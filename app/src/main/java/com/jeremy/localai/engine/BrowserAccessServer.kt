package com.jeremy.localai.engine

import com.topjohnwu.superuser.Shell
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class BrowserAccessServer(private val port: Int) {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(Netty, port = port) {
            routing {
                get("/") {
                    val htmlContent = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <title>Local AI Web & Root Gateway</title>
                            <meta name="viewport" content="width=device-width, initial-scale=1">
                            <style>
                                body { font-family: monospace; background: #121212; color: #00ff00; padding: 20px; margin: 0; }
                                h2 { color: #ffffff; margin-top: 0; }
                                .container { display: flex; flex-direction: column; gap: 10px; height: 90vh; }
                                .panels { display: flex; gap: 10px; flex: 1; min-height: 0; }
                                .panel { flex: 1; display: flex; flex-direction: column; }
                                textarea { flex: 1; background: #000; color: #00ff00; border: 1px solid #333; padding: 10px; font-family: monospace; resize: none; box-sizing: border-box; }
                                input[type=text] { width: 100%; padding: 10px; background: #222; color: #fff; border: 1px solid #444; box-sizing: border-box; }
                                .controls { display: flex; gap: 10px; margin-top: 5px; }
                                button { padding: 10px 20px; background: #333; color: #fff; border: 1px solid #555; cursor: pointer; flex: 1; }
                                button:hover { background: #444; }
                                label { font-weight: bold; color: #aaa; margin-bottom: 4px; display: block; }
                            </style>
                        </head>
                        <body>
                            <div class="container">
                                <h2>Local AI Root & Terminal Gateway</h2>
                                <div class="panels">
                                    <!-- Terminal Shell Panel -->
                                    <div class="panel">
                                        <label>Root Shell Terminal</label>
                                        <textarea id="terminalLog" readonly></textarea>
                                        <div class="controls" style="margin-top: 5px;">
                                            <input type="text" id="cmdInput" placeholder="Enter root command (e.g., top -n 1)..." onkeydown="if(event.key==='Enter') runCommand()">
                                            <button onclick="runCommand()" style="flex: 0 0 100px;">Send</button>
                                        </div>
                                    </div>
                                    <!-- AI Response Window Panel -->
                                    <div class="panel">
                                        <label>AI Response Window</label>
                                        <textarea id="aiResponseLog" readonly placeholder="AI generated or model output will appear here..."></textarea>
                                        <div class="controls">
                                            <button onclick="clearLogs()">Clear All</button>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            <script>
                                const terminalLog = document.getElementById('terminalLog');
                                const aiResponseLog = document.getElementById('aiResponseLog');

                                function appendTerminal(text) {
                                    terminalLog.value += text + "\n";
                                    terminalLog.scrollTop = terminalLog.scrollHeight;
                                }

                                function appendAIResponse(text) {
                                    aiResponseLog.value += text + "\n";
                                    aiResponseLog.scrollTop = aiResponseLog.scrollHeight;
                                }

                                async function runCommand() {
                                    const input = document.getElementById('cmdInput');
                                    const cmd = input.value.trim();
                                    if (!cmd) return;
                                    
                                    appendTerminal("$ " + cmd);
                                    input.value = "";

                                    try {
                                        const response = await fetch('/api/exec?cmd=' + encodeURIComponent(cmd));
                                        const result = await response.json();
                                        if (result.output) {
                                            appendTerminal(result.output);
                                        }
                                        if (result.error) {
                                            appendTerminal("[ERR] " + result.error);
                                        }
                                        appendTerminal("Exit code: " + result.code + "\n");
                                    } catch (e) {
                                        appendTerminal("[HTTP ERROR] " + e.message + "\n");
                                    }
                                }

                                function clearLogs() {
                                    terminalLog.value = "";
                                    aiResponseLog.value = "";
                                }
                                
                                appendTerminal("Terminal initialized. Ready for commands.");
                            </script>
                        </body>
                        </html>
                    """.trimIndent()
                    call.respondText(htmlContent, ContentType.Text.Html)
                }

                get("/api/exec") {
                    val cmd = call.parameters["cmd"] ?: ""
                    if (cmd.isBlank()) {
                        call.respondText("{\"error\": \"Empty command\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@get
                    }

                    val result = Shell.cmd(cmd).exec()
                    val outputStr = result.out.joinToString("\n")
                    val errorStr = result.err.joinToString("\n")
                    val exitCode = result.code

                    val jsonResponse = "{\"output\": " + org.json.JSONObject.quote(outputStr) + 
                                       ", \"error\": " + org.json.JSONObject.quote(errorStr) + 
                                       ", \"code\": $exitCode}"
                    call.respondText(jsonResponse, ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
