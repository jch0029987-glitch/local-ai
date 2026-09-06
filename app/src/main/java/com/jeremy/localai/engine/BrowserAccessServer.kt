package com.jeremy.localai.engine

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class BrowserAccessServer(private val port: Int = 8080) {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(Netty, port = port) {
            routing {
                get("/") {
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <title>LocalAI Remote Browser Node</title>
                            <meta name="viewport" content="width=device-width, initial-scale=1">
                            <style>
                                body { font-family: sans-serif; background: #121212; color: #e0e0e0; padding: 20px; }
                                .card { background: #1e1e1e; padding: 20px; border-radius: 8px; margin-bottom: 15px; }
                                input, button { padding: 10px; margin-top: 5px; background: #2d2d2d; color: #fff; border: 1px solid #444; border-radius: 4px; }
                                button { background: #6200ee; cursor: pointer; border: none; }
                            </style>
                        </head>
                        <body>
                            <h1>LocalAI Android Gateway</h1>
                            <div class="card">
                                <h3>Node Status</h3>
                                <p>Connection State: <span id="status">Active</span></p>
                            </div>
                            <div class="card">
                                <h3>Send Prompt to Local LLM</h3>
                                <textarea id="prompt" rows="3" style="width:100%; background:#2d2d2d; color:#fff; border:1px solid #444;"></textarea><br/>
                                <button onclick="sendPrompt()">Run Inference</button>
                                <pre id="output" style="margin-top:10px; background:#000; padding:10px;"></pre>
                            </div>
                            <script>
                                function sendPrompt() {
                                    const text = document.getElementById('prompt').value;
                                    document.getElementById('output').innerText = "Processing...";
                                    setTimeout(() => {
                                        document.getElementById('output').innerText = "Echo from Android node: " + text;
                                    }, 500);
                                }
                            </script>
                        </body>
                        </html>
                    """.trimIndent()
                    call.respondText(html, ContentType.Text.Html)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
