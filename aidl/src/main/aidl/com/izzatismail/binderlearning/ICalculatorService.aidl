// ICalculatorService.aidl
//
// This is the IPC contract. The AIDL compiler will generate two key classes
// from this file (look in build/generated/aidl_source_output_dir/ after building):
//
//   ICalculatorService.Stub   — the server-side abstract class. The Service
//     extends and overrides its methods. Stub's onTransact() reads the incoming
//     Parcel, dispatches to the correct method, and writes the result back.
//
//   ICalculatorService.Proxy  — the client-side wrapper. When the client calls
//     proxy.add(a, b), Proxy writes (a, b) into a Parcel, sends the transaction
//     through /dev/binder to the kernel driver, which copies the data into the
//     service process. The Stub on the service side reads the Parcel and calls
//     the real implementation.
//
// Primitive types (int, long, boolean, etc.) cross the process boundary by value.
// Directional tags (in / out / inout) only matter for non-primitive Parcelable
// types — they are not needed here.

package com.izzatismail.binderlearning;

interface ICalculatorService {
    int add(int a, int b);
    int subtract(int a, int b);
    int multiply(int a, int b);
    int divide(int a, int b);
}