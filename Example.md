# Binder IPC in Clean Architecture + MVVM

This document shows how the AIDL Proxy (from the calculator project)
fits into a Clean Architecture + MVVM flow, using CarService AC temperature
control as the running example.

This is **not** production code — it's a learning reference showing where
the Binder boundary sits in a layered architecture.

---

## Layer Diagram

```
┌─────────────────────────────────────────────────────┐
│                   View (Activity)                     │
│  - binds to service                                  │
│  - observes StateFlow                                │
│  - no AIDL calls here                                │
└──────────────────────┬──────────────────────────────┘
                       │ observes
                       ▼
┌─────────────────────────────────────────────────────┐
│                    ViewModel                          │
│  - holds UiState / StateFlow                         │
│  - calls repository in viewModelScope                │
│  - pure Kotlin, no Android imports                   │
│  - testable with fake repository                     │
└──────────────────────┬──────────────────────────────┘
                       │ calls
                       ▼
┌─────────────────────────────────────────────────────┐
│                   Repository (interface)              │
│  - domain layer, pure Kotlin                         │
│  - returns Result<T> (Success / Error)               │
│  - Activity/ViewModel know nothing about Binder      │
└──────────────────────┬──────────────────────────────┘
                       │ implemented by
                       ▼
┌─────────────────────────────────────────────────────┐
│             CarAcBridge (Data layer)                  │
│  - wraps the AIDL Proxy                              │
│  - calls on Dispatchers.IO                           │
│  - catches RemoteException → Result.Error            │
│  - THIS is where Binder IPC enters the architecture  │
└──────┬─────────────────────────────────────────┬─────┘
       │ wraps                                   │
       ▼                                         ▼
┌──────────────┐                      ┌──────────────────┐
│  ICarAc      │   ──── Binder ────►  │  CarService      │
│  Proxy       │   kernel copies      │  (ICarAc.Stub)   │
│              │   via /dev/binder    │                  │
│  (client     │                      │  (hardware call) │
│   process)   │                      │  (service proc.) │
└──────────────┘                      └──────────────────┘
```

The key insight: **the Binder boundary is inside the data layer**. The
ViewModel, domain, and View never know Binder exists.

---

## 1. Domain Layer — Pure Kotlin, No Android Imports

```kotlin
// Domain model
data class AcState(
    val driverTempC: Int,
    val passengerTempC: Int
)

// Result type for error handling (replaces RemoteException at domain level)
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String) : Result<Nothing>()
}

// Repository contract — the domain doesn't know about Binder or Parcels
interface AcRepository {
    suspend fun getAcState(): Result<AcState>
    suspend fun setDriverTemp(temp: Int): Result<Unit>
    suspend fun setPassengerTemp(temp: Int): Result<Unit>
}
```

The domain layer defines *what* the app does (read/set AC temperature)
but not *how* (Binder IPC, HAL call, mock).

---

## 2. Data Layer — Where Binder Lives

```kotlin
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.car.ICarAc  // generated AIDL interface

class CarAcBridge(private val proxy: ICarAc) : AcRepository {

    // All AIDL calls run on IO thread — Binder transact() blocks the
    // calling thread, so we must NOT call from the main thread.
    override suspend fun getAcState(): Result<AcState> = withContext(Dispatchers.IO) {
        try {
            val driver = proxy.getDriverTempCelsius()
            val passenger = proxy.getPassengerTempCelsius()
            Result.Success(AcState(driver, passenger))
        } catch (e: RemoteException) {
            // Service process crashed / was killed
            Result.Error("Car service unavailable: ${e.message}")
        }
    }

    override suspend fun setDriverTemp(temp: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            proxy.setDriverTempCelsius(temp)
            Result.Success(Unit)
        } catch (e: RemoteException) {
            Result.Error("Car service error: ${e.message}")
        }
    }
}
```

**Two things happen here:**
1. `RemoteException` (checked, from AIDL) → `Result.Error` (domain-safe)
2. Binder blocking (main thread freeze) → fixed by `withContext(Dispatchers.IO)`

---

## 3. ViewModel Layer

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AcViewModel(private val repository: AcRepository) : ViewModel() {

    private val _state = MutableStateFlow<AcUiState>(AcUiState.Loading)
    val state: StateFlow<AcUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AcEvent>()
    val events: SharedFlow<AcEvent> = _events.asSharedFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = AcUiState.Loading
            when (val result = repository.getAcState()) {
                is Result.Success -> _state.value = AcUiState.Ready(result.data)
                is Result.Error -> {
                    _state.value = AcUiState.Error(result.message)
                    _events.emit(AcEvent.ShowSnackbar(result.message))
                }
            }
        }
    }

    fun increaseDriverTemp() {
        val current = (_state.value as? AcUiState.Ready)?.state ?: return
        viewModelScope.launch {
            repository.setDriverTemp(current.driverTempC + 1)
            load()  // refresh
        }
    }
}

sealed class AcUiState {
    object Loading : AcUiState()
    data class Ready(val state: AcState) : AcUiState()
    data class Error(val message: String) : AcUiState()
}

sealed class AcEvent {
    data class ShowSnackbar(val message: String) : AcEvent()
}
```

The ViewModel has **zero Android framework imports** (no `Context`, no
`RemoteException`, no `ICarAc`). It works with:
- `AcRepository` (interface, can be faked in tests)
- `AcState` (pure data)
- `Result<AcState>` (Success/Error — replaces try/catch)

---

## 4. View Layer (Activity)

```kotlin
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AcActivity : AppCompatActivity() {

    // ViewModelFactory passes the Proxy into CarAcBridge
    private val viewModel: AcViewModel by viewModels {
        AcViewModelFactory(CarAcBridge(calculatorService))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAcBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnWarmer.setOnClickListener { viewModel.increaseDriverTemp() }
        binding.btnCooler.setOnClickListener { viewModel.decreaseDriverTemp() }

        // Observe state — no AIDL calls in the View layer
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is AcUiState.Loading -> binding.tempText.text = "Loading..."
                    is AcUiState.Ready ->
                        binding.tempText.text = "${state.state.driverTempC}°C"
                    is AcUiState.Error ->
                        binding.tempText.text = "Error: ${state.message}"
                }
            }
        }
    }
}
```

The View only:
1. Binds to the service (as we do in the calculator project)
2. Passes the Proxy into the ViewModelFactory
3. Observes `StateFlow`

No AIDL method calls, no `RemoteException` handling, no `try/catch`.

---

## 5. Service Connection — Bridge Between Android and Domain

```kotlin
class AcActivity : AppCompatActivity() {

    private var carAcProxy: ICarAc? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            carAcProxy = ICarAc.Stub.asInterface(service)
            viewModel.load()  // trigger first data fetch
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carAcProxy = null
            // ViewModel observes this through a failed repository call
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val factory = AcViewModelFactory(CarAcBridge(carAcProxy))
        // ... but carAcProxy is null here! See note below.
    }
}
```

**Problem:** `carAcProxy` is null until `onServiceConnected` fires (which
is asynchronous). The ViewModel is created in `onCreate`, before the
service connects.

**Fix:** Use a `proxy: MutableStateFlow<ICarAc?>`, create the ViewModel
with a repository that observes the Flow, and emit the proxy when the
service connects. Or simply re-create the ViewModel on connection.

---

## Summary: Raw AIDL vs Clean Architecture

| Aspect | Raw AIDL (calculator project) | Clean Architecture + MVVM |
|---|---|---|
| Where AIDL is called | Activity directly | CarAcBridge (data layer) |
| Threading | Main thread (UI freezes) | Dispatchers.IO |
| RemoteException | try/catch in Activity | → Result.Error at domain boundary |
| ViewModel | None | Manages UiState/StateFlow |
| Testability | Must run on device | Unit-testable with fake repository |
| Composable | Activity only | Any View (Activity, Fragment, Compose) |

The **Binder IPC itself doesn't change** — Proxy still packs Parcels, Stub
still dispatches `onTransact()`. Clean Architecture just wraps the Proxy
in a repository so the rest of the app never knows Binder exists.