package warne.client.core.hooks;

import warne.client.core.Managers;

public class ManagerShutdownHook extends Thread {
    @Override
    public void run() {
        Managers.FRIEND.saveFriends();
        Managers.CONFIG.save(Managers.CONFIG.getCurrentConfig());
        Managers.WAYPOINT.saveWayPoints();
        Managers.MACRO.saveMacro();
        Managers.PROXY.saveProxies();
        Managers.ADDON.shutDown();
    }
}
