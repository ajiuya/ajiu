package com.aicode.feature.workspace.domain

/**
 * A tool's reviewed source or targets no longer match its actual execution capture.
 * This is an explicit terminal failure, not cancellation or a retryable I/O failure.
 */
class ExecutionSourceChangedException : Exception("Tool execution source changed")
