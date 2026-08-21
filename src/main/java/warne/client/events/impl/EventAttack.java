package warne.client.events.impl;

import net.minecraft.entity.Entity;
import warne.client.events.Event;

public class EventAttack extends Event {
    private Entity entity;
    boolean pre;

    public EventAttack(Entity entity, boolean pre){
        this.entity = entity;
        this.pre = pre;
    }

    public Entity getEntity(){
        return  entity;
    }

    public boolean isPre(){
        return pre;
    }
}
