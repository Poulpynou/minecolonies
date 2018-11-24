package com.minecolonies.coremod.entity.ai.basic;

import com.minecolonies.api.entity.ai.DesiredActivity;
import com.minecolonies.api.entity.ai.Status;
import com.minecolonies.api.util.CompatibilityUtils;
import com.minecolonies.api.util.Log;
import com.minecolonies.coremod.colony.jobs.AbstractJob;
import com.minecolonies.coremod.entity.EntityCitizen;
import com.minecolonies.coremod.entity.ai.util.AIState;
import com.minecolonies.coremod.entity.ai.util.AITarget;
import com.minecolonies.coremod.entity.ai.util.ChatSpamFilter;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Skeleton class for worker ai.
 * Here general target execution will be handled.
 * No utility on this level!
 * That's what {@link AbstractEntityAIInteract} is for.
 *
 * @param <J> the job this ai will have.
 */
public abstract class AbstractAISkeleton<J extends AbstractJob> extends EntityAIBase
{

    private static final int                               MUTEX_MASK = 3;
    @NotNull
    protected final      J                                 job;
    @NotNull
    protected final      EntityCitizen                     worker;
    protected final      World                             world;
    @NotNull
    protected final      ChatSpamFilter                    chatSpamFilter;
    @NotNull
    private final        Map<AIState, ArrayList<AITarget>> targetMap;
    /**
     * The current state the ai is in.
     * Used to compare to state matching targets.
     */
    private              AIState                           state;

    /**
     * Sets up some important skeleton stuff for every ai.
     *
     * @param job the job class.
     */
    protected AbstractAISkeleton(@NotNull final J job)
    {
        super();

        if (!job.getCitizen().getCitizenEntity().isPresent())
        {
            throw new IllegalArgumentException("Cannot instantiate a AI from a Job that is attached to a Citizen without entity.");
        }

        this.targetMap = new HashMap<>();
        setMutexBits(MUTEX_MASK);
        this.job = job;
        this.worker = this.job.getCitizen().getCitizenEntity().get();
        this.world = CompatibilityUtils.getWorld(this.worker);
        this.chatSpamFilter = new ChatSpamFilter(job.getCitizen());
        this.state = AIState.INIT;
        this.targetMap.put(AIState.INIT, new ArrayList<>());
        this.targetMap.put(AIState.AI_BLOCKING_PRIO, new ArrayList<>());
        this.targetMap.put(AIState.STATE_BLOCKING_PRIO, new ArrayList<>());
        this.targetMap.put(AIState.EVENT, new ArrayList<>());
    }

    /**
     * Register one target.
     *
     * @param target the target to register.
     */
    private void registerTarget(final AITarget target)
    {
        if (!targetMap.containsKey(target.getState()))
        {
            final ArrayList<AITarget> newList = new ArrayList<>();
            newList.add(target);
            targetMap.put(target.getState(), newList);
        }
        else
        {
            final ArrayList<AITarget> temp = new ArrayList<>(targetMap.get(target.getState()));
            temp.add(target);
            targetMap.put(target.getState(), temp);
        }
    }

    /**
     * Unregisters an AI Target
     */
    protected final void unRegisterTarget(final AITarget target)
    {
        final ArrayList<AITarget> temp = new ArrayList<>(targetMap.get(target.getState()));
        temp.remove(target);
        targetMap.put(target.getState(), temp);
    }

    /**
     * Register all targets your ai needs.
     * They will be checked in the order of registration,
     * so sort them accordingly.
     *
     * @param targets a number of targets that need registration
     */
    protected final void registerTargets(final AITarget... targets)
    {
        Arrays.asList(targets).forEach(this::registerTarget);
    }

    /**
     * Returns whether the EntityAIBase should begin execution.
     *
     * @return true if execution is wanted.
     */
    @Override
    public final boolean shouldExecute()
    {
        return worker.getDesiredActivity() == DesiredActivity.WORK;
    }

    /**
     * Returns whether an in-progress EntityAIBase should continue executing.
     */
    @Override
    public final boolean shouldContinueExecuting()
    {
        return super.shouldContinueExecuting();
    }

    /**
     * Execute a one shot task or start executing a continuous task.
     */
    @Override
    public final void startExecuting()
    {
        worker.getCitizenStatusHandler().setStatus(Status.WORKING);
    }

    /**
     * Resets the task.
     */
    @Override
    public final void resetTask()
    {
        worker.getCitizenStatusHandler().setStatus(Status.IDLE);
    }

    /**
     * Updates the task.
     */
    @Override
    public final void updateTask()
    {
        // Check targets in order by priority
        if (!targetMap.get(AIState.AI_BLOCKING_PRIO).stream().anyMatch(this::checkOnTarget)
              && !targetMap.get(AIState.EVENT).stream().anyMatch(this::checkOnTarget)
              && !targetMap.get(AIState.STATE_BLOCKING_PRIO).stream().anyMatch(this::checkOnTarget))
        {
            targetMap.get(state).stream().anyMatch(this::checkOnTarget);
        }
    }

    /**
     * Made final to preserve behaviour:
     * Sets a bitmask telling which other tasks may not run concurrently. The test is a simple bitwise AND - if it
     * yields zero, the two tasks may run concurrently, if not - they must run exclusively from each other.
     *
     * @param mutexBits the bits to flag this with.
     */
    @Override
    public final void setMutexBits(final int mutexBits)
    {
        super.setMutexBits(mutexBits);
    }

    /**
     * Checks on one target to see if it has to be executed.
     * It first checks for the state of the ai.
     * If that matches it tests the predicate if the ai
     * wants to run the target.
     * And if that's a yes, runs the target.
     * Tester and target are both error-checked
     * to prevent minecraft from crashing on bad ai.
     *
     * @param target the target to check
     * @return true if this target worked and we should stop executing this tick
     */
    private boolean checkOnTarget(@NotNull final AITarget target)
    {
        try
        {
            if (!target.test())
            {
                return false;
            }
        }
        catch (final RuntimeException e)
        {
            this.onException(e);
            return false;
        }
        return applyTarget(target);
    }

    /**
     * Handle an exception higher up.
     *
     * @param e The exception to be handled.
     */
    protected void onException(final RuntimeException e)
    {
    }

    /**
     * Continuation of checkOnTarget.
     * applies the target and changes the state.
     * if the state is null, execute more targets
     * and don't change state.
     *
     * @param target the target.
     * @return true if it worked.
     */
    private boolean applyTarget(@NotNull final AITarget target)
    {
        final AIState newState;
        try
        {
            newState = target.apply();
        }
        catch (final RuntimeException e)
        {
            Log.getLogger().warn("Action for target " + target + " threw an exception:", e);
            this.onException(e);
            return false;
        }
        if (newState != null)
        {
            if (target.shouldUnregister())
            {
                unRegisterTarget(target);
            }
            state = newState;
            return true;
        }
        return false;
    }

    /**
     * Get the current state the ai is in.
     *
     * @return The current AIState.
     */
    public final AIState getState()
    {
        return state;
    }

    /**
     * Get the level delay.
     *
     * @return by default 10.
     */
    protected int getLevelDelay()
    {
        return 10;
    }

    /**
     * Check if it is okay to eat by checking if the current target is good to eat.
     *
     * @return true if so.
     */
    public boolean isOkayToEat()
    {
        if (targetMap.get(state) == null)
        {
            return false;
        }
        return targetMap.get(state)
                 .stream()
                 .anyMatch(AITarget::isOkayToEat);
    }

    /**
     * Resets the worker AI to Idle state, use with care interrupts all current Actions
     */
    public void resetAIToIdle()
    {
        state = AIState.IDLE;
    }
}
