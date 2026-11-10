package com.tokai.module.feature;

/** TikTok 47.5.3 account facade, verified against X.03Hd.LJFF and its service interface. */
public final class HostAccountAccess {
    private HostAccountAccess() { }

    public static Object userService(ClassLoader loader) throws ReflectiveOperationException {
        try {
            return Class.forName("X.03Hd", false, loader).getMethod("LJFF").invoke(null);
        } catch (ClassNotFoundException legacyHost) {
            return Class.forName("com.ss.android.ugc.aweme.account.AccountProxyService", false, loader)
                    .getMethod("userService").invoke(null);
        }
    }

    public static Object currentUser(ClassLoader loader) throws ReflectiveOperationException {
        Object service = userService(loader);
        return service == null ? null : service.getClass().getMethod("getCurUser").invoke(service);
    }

    public static boolean isLoggedIn(ClassLoader loader) {
        try {
            Object service = userService(loader);
            return service != null && Boolean.TRUE.equals(
                    service.getClass().getMethod("isLogin").invoke(service));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
}
