package com.example.syncstagereceiver.network

import android.content.Context
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import timber.log.Timber
import java.io.File
import java.io.FileInputStream

class StreamingServer(private val context: Context) {

    private var server: NettyApplicationEngine? = null
    private val localVideoDirectory = File(context.filesDir, "videos")

    fun start() {
        if (server != null) return

        try {
            server = embeddedServer(Netty, port = 8080) {
                routing {
                    get("/{videoName}") {
                        val videoName = call.parameters["videoName"]
                        if (videoName.isNullOrEmpty()) {
                            call.respond(HttpStatusCode.BadRequest, "Missing video name")
                            return@get
                        }

                        // Security: Ensure path traversal is not possible
                        val videoFile = File(localVideoDirectory, videoName)
                        if (!videoFile.canonicalPath.startsWith(localVideoDirectory.canonicalPath)) {
                            call.respond(HttpStatusCode.Forbidden, "Access Denied")
                            return@get
                        }

                        if (videoFile.exists() && videoFile.isFile) {
                            Timber.d("P2P: Serving file $videoName to peer.")

                            // Simple file serving using OutputStream
                            // Ktor's LocalFileContent handles Range headers for resume support automatically
                            call.respondOutputStream(ContentType.Video.MP4, HttpStatusCode.OK) {
                                FileInputStream(videoFile).use { input ->
                                    input.copyTo(this)
                                }
                            }
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }
                }
            }.start(wait = false)
            Timber.i("P2P StreamingServer started on port 8080")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start P2P StreamingServer")
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Timber.i("P2P StreamingServer stopped")
    }
}