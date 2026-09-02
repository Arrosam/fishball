package org.areel.fishball.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bytes `Voice.transcribe` sends, against the service that has to accept them.
 *
 * This exists because voice input never once worked, and nothing in the app could say why. The
 * microphone was recording - the file pulled off the phone plays back a whole sentence - and the
 * transcriber was fine, and in between them Ktor's form builder was writing the part name bare,
 * `Content-Disposition: form-data; name=model`, where RFC 7578 asks for a quoted string. The
 * service looked for a field called `model`, did not find one, and refused the upload. Two
 * missing quotation marks, invisible from either end.
 *
 * So the body is assembled by hand there and here, and this is what says the assembly is right.
 * It talks to the live service on purpose: the bug was a disagreement between a client and a
 * server about a header, and nothing short of that server can settle it. The recording is the
 * one made on the phone during the hunt, which is why the expected transcript is a person saying
 * that it still does not work.
 *
 * Skipped without `HYDROGEN_KEY` in the environment, so it costs an ordinary build nothing.
 */
class TranscriptionUploadTest {

    @Test
    fun `the hand-built upload is accepted and transcribed`() {
        val key = System.getenv("HYDROGEN_KEY")?.takeIf { it.isNotBlank() } ?: run {
            println("skipped: set HYDROGEN_KEY to run it")
            return
        }
        val audio = javaClass.getResourceAsStream("/voice-probe.wav")!!.readBytes()

        // Exactly the shape Voice.transcribe sends. Keep the two in step.
        val boundary = "fishball" + audio.size.toString(16)
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                "ASR\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
            ).toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()

        val body = runBlocking {
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 30_000
                    requestTimeoutMillis = 120_000
                }
            }.post("https://llm.areel.org/v1/audio/transcriptions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.parse("multipart/form-data; boundary=$boundary"))
                setBody(head + audio + tail)
            }.bodyAsText()
        }
        println("asr <- $body")
        assertTrue(!body.contains("\"error\""), "the service refused it: $body")
        assertTrue(body.contains("还是不行"), "that is not what the recording says: $body")
    }
}
