package org.jetbrains.ktor.host

import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.content.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.nio.*
import org.jetbrains.ktor.pipeline.*
import org.jetbrains.ktor.util.*
import java.io.*
import java.nio.channels.*
import java.nio.file.*

abstract class BaseApplicationCall(override val application: Application) : ApplicationCall {
    val executionStack = mutableListOf<PipelineExecution<*>>()

    override fun execute(pipeline: Pipeline<ApplicationCall>): PipelineExecution.State {
        require(executionStack.isEmpty())

        application.config.log.trace("# $this")
        val execution = PipelineExecution(this, pipeline.interceptors)
        executionStack.add(execution)
        try {
            execution.proceed()
        } catch(p: PipelineBranchCompleted) {
            return execution.state
        }
    }

    override fun <T : Any> fork(pipeline: Pipeline<T>, value: T,
                                start: (PipelineExecution<*>, PipelineExecution<T>) -> Unit,
                                finish: (PipelineExecution<*>, PipelineExecution<T>) -> Unit): Nothing {
        require(executionStack.isNotEmpty())

        val currentExecution = executionStack.last()
        application.config.log.trace("# ${"  ".repeat(executionStack.size)} ${currentExecution.subject} -> $value")
        currentExecution.fork(value, pipeline,
                start = { p, s ->
                    start(p, s)
                    executionStack.add(s)
                },
                finish = { p, s ->
                    executionStack.remove(s)
                    finish(p, s)
                })
    }

    override fun respond(message: Any): Nothing {
        fork(respond, message, { p, s -> }, { p, s -> p.proceed() })
    }

    override fun interceptRespond(handler: PipelineContext<Any>.(Any) -> Unit) = respond.intercept(handler)

    protected fun sendHeaders(value: Any) {
        if (value is HasETag) {
            response.etag(value.etag())
        }
        if (value is HasLastModified) {
            response.lastModified(value.lastModified)
        }
        if (value is HasContentType && !request.headers.contains(HttpHeaders.Range)) {
            response.contentType(value.contentType)
        }
        if (value is HasContentLength && !request.headers.contains(HttpHeaders.Range)) {
            response.contentLength(value.contentLength)
        }
    }

    private val respond = Pipeline<Any>()

    init {
        respond.intercept { value ->
            sendHeaders(value)
            when (value) {
                is String -> {
                    response.status() ?: response.status(HttpStatusCode.OK)
                    val encoding = response.headers[HttpHeaders.ContentType]?.let {
                        ContentType.parse(it).parameter("charset")
                    } ?: "UTF-8"
                    response.streamText(value, encoding)
                    close()
                }
                is TextContent -> {
                    response.contentType(value.contentType)
                    respond(value.text)
                }
                is HttpStatusContent -> {
                    response.status(value.code)
                    respond(TextContent(ContentType.Text.Html, "<H1>${value.code}</H1>${value.message}"))
                }
                is HttpStatusCode -> {
                    response.status(value)
                    close()
                }
                is StreamContent -> {
                    response.stream {
                        value.stream(this)
                    }
                    close()
                }
                is URIFileContent -> {
                    if (value.uri.scheme == "file") {
                        respond(LocalFileContent(File(value.uri)))
                    } else {
                        sendStream(value.stream())
                        close()
                    }
                }
                is LocalFileContent -> {
                    handleRangeRequest(value, value.file.length(), mergeToSingleRange = false) { ranges ->
                        when {
                            ranges == null -> {
                                // TODO compression settings
                                response.contentType(value.contentType)
                                response.status(HttpStatusCode.OK)

                                if (request.acceptEncodingItems().any { it.value == "gzip" }) {
                                    response.headers.append(HttpHeaders.ContentEncoding, "gzip")
                                    sendAsyncChannel(value.file.asyncReadOnlyFileChannel().deflated())
                                } else {
                                    sendFile(value.file, 0L, value.file.length())
                                }
                            }
                            ranges.size == 1 -> {
                                response.contentType(value.contentType)
                                response.status(HttpStatusCode.PartialContent)

                                val single = ranges.single()
                                response.contentRange(single, value.file.length(), RangeUnits.Bytes)
                                sendFile(value.file, single.start, single.length)
                            }
                            else -> {
                                val boundary = "ktor-boundary-" + nextNonce()
                                response.status(HttpStatusCode.PartialContent)
                                response.contentType(ContentType.MultiPart.ByteRanges.withParameter("boundary", boundary))

                                sendAsyncChannel(ByteRangesChannel(ranges.map {
                                    ByteRangesChannel.FileWithRange(value.file, it)
                                }, boundary, value.contentType.toString()))
                            }
                        }

                        close()
                    }
                }
                is StreamContentProvider -> {
                    sendStream(value.stream())
                    close()
                }
            }
        }
    }


    protected open fun sendAsyncChannel(channel: AsynchronousByteChannel) {
        response.stream {
            Channels.newInputStream(channel).use { it.copyTo(this) }
        }
        close()
    }

    protected open fun sendFile(file: File, position: Long, length: Long) {
        response.stream {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
                fc.transferTo(position, length, Channels.newChannel(this))
            }
        }
        close()
    }

    protected open fun sendStream(stream: InputStream) {
        response.stream {
            stream.use { it.copyTo(this) }
        }
        close()
    }
}