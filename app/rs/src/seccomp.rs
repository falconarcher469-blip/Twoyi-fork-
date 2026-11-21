// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

#[cfg(target_arch = "aarch64")]
mod imp {
    use libc::{c_int, c_void, sigaction, siginfo_t, sigemptyset, ucontext_t, ENOSYS, SA_NODEFER, SA_SIGINFO, SIGSYS};
    use std::mem;

    // ARM64 Linux 5.x/6.x syscalls often blocked by host seccomp or unhandled by older guest bionic
    const SYS_RSEQ: u64 = 293;
    const SYS_CLONE3: u64 = 435;
    const SYS_CLOSE_RANGE: u64 = 436;
    const SYS_FACCESSAT2: u64 = 439;
    const SYS_EPOLL_PWAIT2: u64 = 441;
    const SYS_MEMFD_SECRET: u64 = 447;
    const SYS_PROCESS_MRELEASE: u64 = 448;
    const SYS_FUTEX_WAITV: u64 = 449;

    fn is_graceful_enosys_syscall(nr: u64) -> bool {
        matches!(
            nr,
            SYS_RSEQ
                | SYS_CLONE3
                | SYS_CLOSE_RANGE
                | SYS_FACCESSAT2
                | SYS_EPOLL_PWAIT2
                | SYS_MEMFD_SECRET
                | SYS_PROCESS_MRELEASE
                | SYS_FUTEX_WAITV
        ) || nr >= 424
    }

    extern "C" fn sigsys_handler(_sig: c_int, _info: *mut siginfo_t, ucontext: *mut c_void) {
        if ucontext.is_null() {
            return;
        }
        unsafe {
            let uc = &mut *(ucontext as *mut ucontext_t);
            // ARM64 Linux ABI: x8 holds syscall number, x0 holds return value
            let nr = uc.uc_mcontext.regs[8];
            if is_graceful_enosys_syscall(nr) {
                uc.uc_mcontext.regs[0] = (-ENOSYS as i64) as u64;
            } else {
                uc.uc_mcontext.regs[0] = (-ENOSYS as i64) as u64;
            }
        }
    }

    pub fn install_sigsys_handler() {
        unsafe {
            let mut sa: sigaction = mem::zeroed();
            sa.sa_sigaction = sigsys_handler as usize;
            sa.sa_flags = SA_SIGINFO | SA_NODEFER;
            sigemptyset(&mut sa.sa_mask);
            let _ = sigaction(SIGSYS, &sa, std::ptr::null_mut());
        }
    }

    pub unsafe fn configure_child_process() {
        let mut set: libc::sigset_t = mem::zeroed();
        sigemptyset(&mut set);
        libc::sigaddset(&mut set, SIGSYS);
        libc::sigprocmask(libc::SIG_UNBLOCK, &set, std::ptr::null_mut());
        install_sigsys_handler();
    }
}

#[cfg(not(target_arch = "aarch64"))]
mod imp {
    pub fn install_sigsys_handler() {}
    pub unsafe fn configure_child_process() {}
}

pub use imp::{configure_child_process, install_sigsys_handler};
