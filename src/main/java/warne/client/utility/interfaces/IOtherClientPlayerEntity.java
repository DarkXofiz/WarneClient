package warne.client.utility.interfaces;

import warne.client.features.modules.combat.Aura;

public interface IOtherClientPlayerEntity {
    void resolve(Aura.Resolver mode);

    void releaseResolver();
}
