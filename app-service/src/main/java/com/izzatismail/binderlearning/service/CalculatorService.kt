// CalculatorService — the remote calculator implementation.
//
// This is a Bound Service. onBind() returns a Binder object (the Stub).
// The Stub is the server-side half of the Binder IPC pair:
//   - Client holds an ICalculatorService Proxy (wraps a remote IBinder reference)
//   - Service exposes an ICalculatorService.Stub (extends Binder, dispatches onTransact)
//
// The object returned by onBind() is NOT the CalculatorService instance itself.
// It is a Binder token (an IBinder) that the kernel uses to route transactions.
// The client never has a direct reference to this object — it communicates
// through the kernel driver (/dev/binder) using the generated Proxy.

package com.izzatismail.binderlearning.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.izzatismail.binderlearning.ICalculatorService

private const val TAG = "CalculatorService"

class CalculatorService : Service() {

    // The Stub is the generated abstract class.
    // We override its four methods to provide the actual calculation logic.
    private val binder = object : ICalculatorService.Stub() {

        override fun add(a: Int, b: Int): Int {
            Log.d(TAG, "add($a, $b) called on thread: ${Thread.currentThread().name}")
            return a + b
        }

        override fun subtract(a: Int, b: Int): Int {
            Log.d(TAG, "subtract($a, $b) called on thread: ${Thread.currentThread().name}")
            return a - b
        }

        override fun multiply(a: Int, b: Int): Int {
            Log.d(TAG, "multiply($a, $b) called on thread: ${Thread.currentThread().name}")
            return a * b
        }

        override fun divide(a: Int, b: Int): Int {
            Log.d(TAG, "divide($a, $b) called on thread: ${Thread.currentThread().name}")
            if (b == 0) {
                Log.w(TAG, "divide-by-zero attempted — returning 0, service process NOT crashed")
                return 0
            }
            // Artificial delay to demonstrate Binder thread pool behavior.
            // This runs on a Binder thread pool thread (NOT the service main thread),
            // so it doesn't block the service. But the client's calling thread
            // (often main thread) will block waiting for the response.
            // The fix: call from a background thread.
            Thread.sleep(2000)
            return a / b
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        // Returning the Stub here.
        // The Stub IS-A Binder, and Binder IS-A IBinder.
        // The kernel registers this Binder node so that remote clients
        // can send transactions to it through /dev/binder.
        Log.d(TAG, "onBind() — returning Stub instance")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }
}