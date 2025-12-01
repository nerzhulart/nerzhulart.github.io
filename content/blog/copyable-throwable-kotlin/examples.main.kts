#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.concurrent.thread

// ============================================================================
// Shared utility classes
// ============================================================================

class DatabaseClient {
    fun fetchDataFromSource(): String {
        // Simulate a database error
        throw java.sql.SQLException("Connection timeout")
    }
}

// ============================================================================
// PROBLEM: Lost Stack Traces
// ============================================================================

class DataProcessorWithoutCopyable {
    private val result = CompletableDeferred<String>()
    private val dbClient = DatabaseClient()

    fun startProcessing() {
        thread(name = "worker-thread") {
            try {
                val data = dbClient.fetchDataFromSource()
                result.complete(data)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
    }

    suspend fun awaitResult(): String {
        return result.await()
    }
}

suspend fun handleDataWithoutCopyable() {
    val processor = DataProcessorWithoutCopyable()
    processor.startProcessing()

    try {
        val data = processor.awaitResult()
        println("Got data: $data")
    } catch (e: Exception) {
        println("=== Problem: Lost Stack Trace ===")
        println("Notice: You only see the worker thread stack, not the coroutine that called await()")
        e.printStackTrace()
    }
}

// ============================================================================
// NAIVE SOLUTION: Manual Exception Wrapping
// ============================================================================

class DataProcessorWithManualWrapping {
    private val result = CompletableDeferred<String>()
    private val dbClient = DatabaseClient()

    fun startProcessing() {
        thread(name = "worker-thread") {
            try {
                val data = dbClient.fetchDataFromSource()
                result.complete(data)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
    }

    suspend fun awaitResult(): String {
        return try {
            result.await()
        } catch (e: Exception) {
            // Manual wrapping to preserve current stack trace
            throw IllegalStateException("Failed to get result", e)
        }
    }
}

suspend fun handleDataWithManualWrapping() {
    val processor = DataProcessorWithManualWrapping()
    processor.startProcessing()

    try {
        val data = processor.awaitResult()
        println("Got data: $data")
    } catch (e: IllegalStateException) {
        println("=== Naive Solution: Manual Wrapping ===")
        println("Notice: Exception type is now IllegalStateException, not the original SQLException")
        println("You have both stacks, but exception handling becomes awkward")
        e.printStackTrace()
    } catch (e: Exception) {
        println("This catch block won't be reached because we wrapped in IllegalStateException")
    }
}

// ============================================================================
// ELEGANT SOLUTION: CopyableThrowable
// ============================================================================

class DataFetchException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause), CopyableThrowable<DataFetchException> {

    override fun createCopy(): DataFetchException {
        return DataFetchException(message ?: "Data fetch failed", this)
    }
}

class DataProcessorWithCopyable {
    private val result = CompletableDeferred<String>()
    private val dbClient = DatabaseClient()

    fun startProcessing() {
        thread(name = "worker-thread") {
            try {
                val data = dbClient.fetchDataFromSource()
                result.complete(data)
            } catch (e: Exception) {
                // Wrap in CopyableThrowable
                result.completeExceptionally(
                    DataFetchException("Failed to fetch data", e)
                )
            }
        }
    }

    suspend fun awaitResult(): String {
        return result.await()
    }
}

suspend fun handleDataWithCopyable() {
    val processor = DataProcessorWithCopyable()
    processor.startProcessing()

    try {
        val data = processor.awaitResult()
        println("Got data: $data")
    } catch (e: Exception) {
        println("=== Solution: Complete Stack Trace ===")
        println("Notice: You see both the await() call AND the worker thread stack")
        e.printStackTrace()
    }
}

// ============================================================================
// HANDLING NON-COPYABLE JDK EXCEPTIONS
// ============================================================================

// Approach 1: Wrap in domain exception
class DataProcessor {
    private val result = CompletableDeferred<String>()

    fun startProcessing() {
        thread(name = "worker-thread") {
            try {
                // Simulate different types of errors
                throw IOException("Network connection failed")
            } catch (e: IOException) {
                // Wrap JDK exception in copyable domain exception
                result.completeExceptionally(
                    DataFetchException("Network error", e)
                )
            }
        }
    }

    suspend fun awaitResult(): String {
        return result.await()
    }
}

// Approach 2: Create copyable wrapper for specific JDK exception
class CopyableIOException(
    message: String?,
    cause: Throwable? = null
) : IOException(message, cause), CopyableThrowable<CopyableIOException> {

    override fun createCopy(): CopyableIOException {
        return CopyableIOException(message, this)
    }
}

class DataProcessorWithCopyableWrapper {
    private val result = CompletableDeferred<String>()

    fun startProcessing() {
        thread(name = "worker-thread") {
            try {
                throw IOException("Network connection failed")
            } catch (e: IOException) {
                result.completeExceptionally(
                    CopyableIOException(e.message, e)
                )
            }
        }
    }

    suspend fun awaitResult(): String {
        return result.await()
    }
}

suspend fun demonstrateWrappingApproach() {
    println("=== Approach 1: Wrap in Domain Exception ===")
    val processor = DataProcessor()
    processor.startProcessing()

    try {
        processor.awaitResult()
    } catch (e: DataFetchException) {
        println("Caught domain exception with full stack trace:")
        e.printStackTrace()
    }
}

suspend fun demonstrateCopyableWrapper() {
    println("\n=== Approach 2: Copyable Wrapper ===")
    val processor = DataProcessorWithCopyableWrapper()
    processor.startProcessing()

    try {
        processor.awaitResult()
    } catch (e: IOException) {
        println("Caught IOException (still the same type) with full stack trace:")
        e.printStackTrace()
    }
}

// ============================================================================
// MAIN ENTRY POINT
// ============================================================================

val example = args.firstOrNull() ?: "all"

runBlocking {
    when (example) {
        "problem" -> {
            println("Running: Problem - Lost Stack Trace")
            println("=".repeat(50))
            handleDataWithoutCopyable()
        }
        "wrapping" -> {
            println("Running: Naive Solution - Manual Wrapping")
            println("=".repeat(50))
            handleDataWithManualWrapping()
        }
        "solution" -> {
            println("Running: Solution - CopyableThrowable")
            println("=".repeat(50))
            handleDataWithCopyable()
        }
        "noncopyable" -> {
            println("Running: Handling Non-Copyable JDK Exceptions")
            println("=".repeat(50))
            demonstrateWrappingApproach()
            demonstrateCopyableWrapper()
        }
        "all" -> {
            println("Running all examples...")
            println()

            println("1. PROBLEM: Lost Stack Trace")
            println("=".repeat(50))
            handleDataWithoutCopyable()
            println("\n")

            println("2. NAIVE SOLUTION: Manual Wrapping")
            println("=".repeat(50))
            handleDataWithManualWrapping()
            println("\n")

            println("3. ELEGANT SOLUTION: CopyableThrowable")
            println("=".repeat(50))
            handleDataWithCopyable()
            println("\n")

            println("4. HANDLING NON-COPYABLE JDK EXCEPTIONS")
            println("=".repeat(50))
            demonstrateWrappingApproach()
            demonstrateCopyableWrapper()
        }
        else -> {
            println("Unknown example: $example")
            println("Available options: problem, wrapping, solution, noncopyable, all")
        }
    }
}
