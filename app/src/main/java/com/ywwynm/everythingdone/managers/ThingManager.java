package com.ywwynm.everythingdone.managers;

import android.content.Context;
import android.database.Cursor;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.Def;
import com.ywwynm.everythingdone.database.HabitDAO;
import com.ywwynm.everythingdone.database.ReminderDAO;
import com.ywwynm.everythingdone.database.ThingDAO;
import com.ywwynm.everythingdone.helpers.AutoNotifyHelper;
import com.ywwynm.everythingdone.helpers.CheckListHelper;
import com.ywwynm.everythingdone.model.Reminder;
import com.ywwynm.everythingdone.model.Thing;
import com.ywwynm.everythingdone.model.ThingsCounts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by ywwynm on 2015/9/6.
 * Controller for {@link Thing}.
 * Containing a {@link ThingDAO} member {@link mDao} to operate database.
 */
public class ThingManager {

    private Context mContext;

    private ThingDAO mDao;

    /**
     * The limit for getting and controlling things from/in database.
     * Also means current state of UI in Activities.
     *
     * Should be one of:
     * {@link ALL_UNDERWAY}
     * {@link NOTE_UNDERWAY}
     * {@link REMINDER_UNDERWAY}
     * {@link HABIT_UNDERWAY}
     * {@link GOAL_UNDERWAY}
     * {@link ALL_FINISHED}
     * {@link ALL_DELETED}
     */
    private int mLimit;

    private List<Thing> mThings;
    private ThingsCounts mThingsCounts;

    /**
     * Used to ensure that id/location of thing in {@link mThings} is same as that in database.
     *
     * Every time {@link com.ywwynm.everythingdone.activities.DetailActivity}
     * creates a new thing and inserts it into the database, we needs to do the job
     * to {@link mThings}, too. So keeping their latest joined ones as the same is essential,
     * especially for thing's ID/location.
     *
     * Not an elegant design for EverythingDone but it works fine, at least right now.
     */
    private long mHeaderId;

    private ExecutorService mExecutor;

    private static ThingManager sThingManager;

    private boolean mIsHandlingUndo = false;
    private List<Long> mUndoHabits;
    private List<Reminder> mUndoGoals;

    private ThingManager(Context context) {
        mContext = context;
        mDao = ThingDAO.getInstance(context);

        setLimit(Def.LimitForGettingThings.ALL_UNDERWAY, true);

        mThingsCounts = ThingsCounts.getInstance(context);

        mHeaderId = mThings.get(0).getId();

        mExecutor = Executors.newSingleThreadExecutor();

        mUndoHabits = new ArrayList<>();
        mUndoGoals  = new ArrayList<>();
    }

    public static ThingManager getInstance(Context context) {
        if (sThingManager == null) {
            synchronized (ThingManager.class) {
                if (sThingManager == null) {
                    sThingManager = new ThingManager(context);
                }
            }
        }
        return sThingManager;
    }

    public void setLimit(int limit, boolean loadThingsNow) {
        mLimit = limit;
        mDao.setLimit(limit);
        if (loadThingsNow) {
            loadThings();
        }
    }

    public List<Reminder> getUndoGoals() {
        return mUndoGoals;
    }

    public void loadThings() {
        mThings = mDao.getThingsForDisplay(mLimit);

        // do self-check to prevent wrong display for normal and empty states.
        int size = mThings.size();
        if (size == 1) {
            create(Thing.generateNotifyEmpty(mLimit, getHeaderId(), mContext), false, true);
        } else if (size > 2) {
            int pos = -1;
            Thing thing, notifyEmpty = null;
            for (int i = 1; i < size; i++) {
                thing = mThings.get(i);
                if (thing.getType() >= Thing.NOTIFY_EMPTY_UNDERWAY) {
                    pos = i;
                    notifyEmpty = thing;
                    break;
                }
            }
            if (pos != -1) {
                updateState(notifyEmpty, pos, -1, Thing.UNDERWAY, Thing.DELETED_FOREVER, false, false);
            }
        }
    }

    public List<Thing> getThings() {
        return mThings;
    }

    public ThingsCounts getThingsCounts() {
        return mThingsCounts;
    }

    public long getHeaderId() {
        return mHeaderId;
    }

    public void searchThings(String keyword, int color) {
        List<Thing> things = mDao.getThingsForDisplay(mLimit, keyword, color);
        final String PTP = Thing.PRIVATE_THING_PREFIX;
        boolean containsPtp = false;
        for (int i = 0; i < PTP.length() && !containsPtp; i++) {
            if (keyword.contains(String.valueOf(PTP.charAt(i)))) {
                containsPtp = true;
            }
        }

        if (CheckListHelper.isSignalContainsStrIgnoreCase(keyword) || containsPtp) {
            mThings.clear();
            mThings.add(mDao.getThingById(mDao.getHeaderId()));
            for (Thing thing : things) {
                if (thing.getType() == Thing.HEADER) continue;

                String regex = "";
                for (int i = 0; i < CheckListHelper.CHECK_STATE_NUM; i++) {
                    regex += CheckListHelper.SIGNAL + i + "|";
                }
                regex = regex.substring(0, regex.length() - 1);
                String content = thing.getContent().replaceAll(regex, "");
                String title   = thing.getTitle().replaceAll(PTP, "");

                if (content.contains(keyword) || title.contains(keyword)) {
                    mThings.add(thing);
                }
            }
        } else {
            mThings = things;
        }
    }

    /**
     * Create a new thing.
     *
     * @param thingToCreate the thing to create.
     * @param handleNotifyEmpty whether we should handle deletion/creation of NOTIFY_EMPTYs.
     * @return {@code true} if we found a need-to-delete NOTIFY_EMPTY under current
     *          limit({@link mLimit}) and we deleted it indeed, which means
     *          {@link com.ywwynm.everythingdone.activities.ThingsActivity} need to call
     *          {@link com.ywwynm.everythingdone.adapters.ThingsAdapter#notifyItemChanged(int)}.
     *          {@code false} otherwise and should call ThingsAdapter#notifyItemInserted(1).
     */
    public boolean create(final Thing thingToCreate, boolean handleNotifyEmpty, boolean addToThingsNow) {
        // create in database at first
        thingToCreate.setId(mHeaderId);
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mDao.create(thingToCreate, true, false);
            }
        });

        updateHeader(1);

        // see if we can delete a NOTIFY_EMPTY
        boolean deletedNEnow = false;
        int type = thingToCreate.getType();
        if (handleNotifyEmpty && !App.isSearching) {
            deletedNEnow = deleteNEnow(type, Thing.UNDERWAY);
        }

        if (addToThingsNow) {
            mThings.add(1, thingToCreate);
        }

        if (type >= Thing.NOTE && type <= Thing.GOAL) {
            AutoNotifyHelper.createAutoNotify(thingToCreate, mContext);
        }

        mThingsCounts.handleCreation(type);

        return deletedNEnow;
    }

    /**
     * This method will be called to update a {@link Thing}'s content(including {@link Thing.type},
     * {@link Thing.title}, {@link Thing.content} and so on. NOT including {@link Thing.state}).
     * And this method will be only called when {@param updatedThing} is in {@link mThings}.
     *
     * @param typeBefore old type of {@param updatedThing}
     * @param updatedThing thing whose content is updated.
     * @param position position {@param updatedThing}'s position in {@link mThings}.
     * @param handleNotifyEmpty whether we should handle deletion/creation of NOTIFY_EMPTYs.
     * @return 0 if update really happens and {@link com.ywwynm.everythingdone.activities.ThingsActivity}
     *         should call {@link com.ywwynm.everythingdone.adapters.ThingsAdapter#notifyItemChanged(int)}.
     *
     *         1 if we updated a thing to a new type and created a NOTIFY_EMPTY for current limit
     *         ({@link mLimit}) so that ThingsActivity should call ThingsAdapter#notifyItemChanged(1).
     *
     *         2 if we updated a thing to a new type but didn't create a NOTIFY_EMPTY for current limit.
     *         In this situation, ThingsActivity should call ThingsAdapter#notifyItemRemoved({@param position}).
     */
    public int update(@Thing.Type final int typeBefore, final Thing updatedThing, int position,
                      boolean handleNotifyEmpty) {
        if (handleNotifyEmpty &&
                willCreateNEforOtherLimit(
                        updatedThing.getId(), typeBefore, updatedThing.getState(), false)) {
            updateHeader(1);
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mDao.update(typeBefore, updatedThing, true, false);
            }
        });

        int state     = updatedThing.getState();
        int typeAfter = updatedThing.getType();
        mThingsCounts.handleUpdate(typeBefore, state, typeAfter, state, 1);

        if (handleNotifyEmpty && !App.isSearching) {
            deleteNEnow(typeAfter, state);
        }

        if (mLimit == Def.LimitForGettingThings.ALL_UNDERWAY ||
                Thing.sameType(typeBefore, typeAfter)) {
            // will not generate NOTIFY_EMPTY
            return 0;
        } else {
            mThings.remove(position);

            boolean createdNEnow = false;
            if (handleNotifyEmpty) {
                createdNEnow = createNEnow(typeBefore, state, !App.isSearching);
            }

            return createdNEnow ? 1 : 2;
        }
    }

    /**
     * Updates thing's state.
     * This method will be only called when {@param thing} is in {@link mThings}.
     *
     * @param thing the {@link Thing} object to update state.
     * @param position {@param thing}'s position in {@link mThings}.
     * @param location {@param thing}'s location. When a thing's state has been changed, its
     *                  location should be the biggest of all things so that it can appear at
     *                  the top of UI list. But when we undo the change of state, we should also
     *                  make it appear at the same position again and forever so we need to keep
     *                  its location in the beginning.
     * @param stateBefore old state of {@param thing}. The reason why we need to set this param
     *                    is that we can also handle update for state when old state is
     *                    {@link Thing.DELETED_FOREVER}, which we cannot get by
     *                    {@code thing.getState()}.
     * @param stateAfter the state to update to.
     * @param toUndo whether calling this method is to undo update or not.
     * @param handleNotifyEmpty whether we should handle deletion/creation of NOTIFY_EMPTYs.
     * @return {@code true} if a NOTIFY_EMPTY is deleted/created for current limit({@link mLimit})
     *          thus {@link com.ywwynm.everythingdone.activities.ThingsActivity} should call
     *          {@link com.ywwynm.everythingdone.adapters.ThingsAdapter#notifyItemChanged(int)}.
     *          {@code false} otherwise, which should call ThingsAdapter#notifyItemInserted(int)
     *          when {@param toUndo} is true or ThingsAdapter#notifyItemRemoved(int) when
     *          {@param toUndo} is false.
     */
    public boolean updateState(final Thing thing, int position, final long location,
                               @Thing.State final int stateBefore, @Thing.State final int stateAfter,
                               final boolean toUndo, final boolean handleNotifyEmpty) {
        if (handleNotifyEmpty &&
                willCreateNEforOtherLimit(thing.getId(), thing.getType(), stateBefore, true)) {
            updateHeader(1);
        }

        final Thing thingToUpdate;
        if (!toUndo) {
            thingToUpdate = Thing.getSameCheckStateThing(thing, stateBefore, stateAfter);
        } else thingToUpdate = thing;

        final long headerId = mHeaderId;
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mDao.updateState(thingToUpdate, location, stateBefore, stateAfter,
                        handleNotifyEmpty, false, toUndo, headerId, true);
            }
        });

        int type = thing.getType();
        boolean deletedNEnow = false;
        if (handleNotifyEmpty && !App.isSearching) {
            deletedNEnow = deleteNEnow(type, stateAfter);
        }

        if (toUndo) {
            mThings.add(position, thing);
        } else {
            // used to make sure that updated thing is at first location except header
            if (stateAfter != Thing.DELETED_FOREVER) {
                updateHeader(1);
            }
            if (mThings.indexOf(thing) == position && position != -1) {
                mThings.remove(position);
            }
        }

        long id = thing.getId();
        if (type == Thing.GOAL) {
            ReminderDAO rDao = ReminderDAO.getInstance(mContext);
            if (!toUndo && stateAfter == Thing.UNDERWAY) {
                Reminder goal = rDao.getReminderById(id);
                mUndoGoals.add(goal);
                rDao.resetGoal(goal);
            } else if (toUndo && stateBefore == Thing.UNDERWAY) {
                mIsHandlingUndo = true;
                rDao.update(mUndoGoals.remove(mUndoGoals.size() - 1)); // undo reset goal
                mIsHandlingUndo = false;
            }
        }
        if (type == Thing.HABIT) {
            HabitDAO habitDAO = HabitDAO.getInstance(mContext);
            if (!toUndo) {
                long curTime = System.currentTimeMillis();
                if (stateAfter == Thing.UNDERWAY) {
                    habitDAO.updateHabitToLatest(id, true, true);
                    habitDAO.addHabitIntervalInfo(id, curTime + ";");
                } else {
                    habitDAO.addHabitIntervalInfo(id, curTime + ",");
                }
            } else {
                habitDAO.removeLastHabitIntervalInfo(id);
            }
        }

        mThingsCounts.handleUpdate(type, stateBefore, type, stateAfter, 1);

        boolean createdNEnow = false;
        if (handleNotifyEmpty) {
            createdNEnow = createNEnow(type, stateBefore, !App.isSearching);
        }
        return deletedNEnow || createdNEnow;
    }

    /**
     * Update states for a number of things with same old state.
     *
     * @param things things whose states need to update.
     * @param stateBefore old state of thing inside {@param things}.
     * @param stateAfter state to update to.
     * @param handleNotifyEmpty whether we should handle deletion/creation of NOTIFY_EMPTYs.
     * @return things' positions in {@link mThings}. Because updating thing's state means deleting
     *          them one by one in {@link mThings}. After every deletion, positions of the rest of
     *          {@link mThings} will change so that we need to find correct position ourselves rather
     *          than use their original positions. Returning this arrayList helps us undo updates.
     *          See {@link ThingManager#undoUpdateStates(List, List, int, int, boolean)} for more details.
     */
    public List<Integer> updateStates(
            List<Thing> things, @Thing.State final int stateBefore, @Thing.State final int stateAfter) {
        final List<Thing> clonedThings = new ArrayList<>();
        Thing temp;
        for (Thing thing : things) {
            temp = Thing.getSameCheckStateThing(thing, stateBefore, stateAfter);
            clonedThings.add(temp);
        }

        final long headerId = mHeaderId;
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mDao.updateStates(clonedThings, null, stateBefore, stateAfter, false, headerId);
            }
        });

        // things.get(0).getType() will lead us to current limit.
        int type = things.get(0).getType();
        if (!App.isSearching) {
            deleteNEnow(type, stateAfter);
        }

        /*
            We don't know how many NEs will be created then so we directly assume that the
            number is 6(except for current limit, which will be handled at last). As a result,
            we should update header id to be size+6.
            This is stupid but it's the only way to do the stuff. QAQ.
         */
        int size = things.size();
        updateHeader(size + 6);

        final ReminderDAO rDao = ReminderDAO.getInstance(mContext);
        List<Integer> positions = new ArrayList<>(size);
        HashMap<Integer, Integer> updateCounts = new HashMap<>();
        for (Thing thing : things) {
            int pos = mThings.indexOf(thing);
            positions.add(pos);

            mThings.remove(thing);

            long id = thing.getId();
            type = thing.getType();
            if (type == Thing.HABIT) {
                mUndoHabits.add(id);
            } else if (type == Thing.GOAL && stateAfter == Thing.UNDERWAY) {
                mUndoGoals.add(rDao.getReminderById(id));
            }

            Integer count = updateCounts.get(type);
            updateCounts.put(type, count == null ? 1 : count + 1);
        }

        for (Map.Entry<Integer, Integer> entry : updateCounts.entrySet()) {
            int t = entry.getKey();
            int v = entry.getValue();
            mThingsCounts.handleUpdate(t, stateBefore, t, stateAfter, v);
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                for (Reminder goal : mUndoGoals) {
                    rDao.resetGoal(goal);
                }

                HabitDAO habitDAO = HabitDAO.getInstance(mContext);
                long curTime = System.currentTimeMillis();
                if (stateAfter == Thing.UNDERWAY) {
                    for (Long habitId : mUndoHabits) {
                        habitDAO.updateHabitToLatest(habitId, true, true);
                        habitDAO.addHabitIntervalInfo(habitId, curTime + ";");
                    }
                } else {
                    for (Long habitId : mUndoHabits) {
                        habitDAO.addHabitIntervalInfo(habitId, curTime + ",");
                    }
                }
            }
        });

        createNEnow(type, stateBefore, !App.isSearching);

        return positions;
    }

    /**
     * undo update for states of a number of things.
     * This method can be only called after {@link ThingManager#updateStates(List, int, int, boolean)}.
     *
     * @param things things to undo updating states.
     * @param positions appropriate positions of things used when {@param things}
     *                  are added to {@link mThings}.
     * @param locations original locations of things.
     * @param stateBefore old state of thing inside {@param things}. The reason why we need to
     *                    set this param is that we can also handle update for states when old
     *                    state is {@link Thing.DELETED_FOREVER}, which we cannot get by
     *                    {@code thing.getState()}.
     * @param stateAfter state to update to.
     * @param handleNotifyEmpty whether we should handle deletion/creation of NOTIFY_EMPTYs.
     */
    public void undoUpdateStates(List<Thing> things, final List<Integer> positions,
                                 List<Long> locations,
                                 @Thing.State final int stateBefore,
                                 @Thing.State final int stateAfter) {
        final List<Thing> clonedThings = new ArrayList<>();
        for (Thing thing : things) {
            clonedThings.add(thing);
        }
        final List<Long> clonedLocations = new ArrayList<>();
        for (Long location : locations) {
            clonedLocations.add(location);
        }

        final long headerId = mHeaderId;
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mDao.updateStates(clonedThings, clonedLocations, stateBefore, stateAfter,
                        true, headerId);
            }
        });

        updateHeader(6);

        int type = things.get(0).getType();
        if (!App.isSearching) {
            deleteNEnow(type, stateAfter);
        }

        int size = things.size();
        HashMap<Integer, Integer> updateCounts = new HashMap<>();
        Thing thing;
        for (int i = size - 1; i >= 0; i--) {
            thing = things.get(i);
            type = thing.getType();
            mThings.add(positions.get(i), thing);

            Integer count = updateCounts.get(type);
            updateCounts.put(type, count == null ? 1 : count + 1);
        }

        for (Map.Entry<Integer, Integer> entry : updateCounts.entrySet()) {
            int t = entry.getKey();
            int v = entry.getValue();
            mThingsCounts.handleUpdate(t, stateBefore, t, stateAfter, v);
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mIsHandlingUndo = true;
                ReminderDAO reminderDAO = ReminderDAO.getInstance(mContext);
                for (Reminder goal : mUndoGoals) {
                    reminderDAO.update(goal);
                }
                mUndoGoals.clear();

                HabitDAO habitDAO = HabitDAO.getInstance(mContext);
                for (Long habitId : mUndoHabits) {
                    habitDAO.removeLastHabitIntervalInfo(habitId);
                }
                mUndoHabits.clear();
                mIsHandlingUndo = false;
            }
        });

        createNEnow(type, stateBefore, !App.isSearching);
    }

    public void clearLists() {
        if (mIsHandlingUndo) {
            return;
        }
        mUndoGoals.clear();
        mUndoHabits.clear();
    }

    /**
     * move a thing from one position to another inside {@link mThings}.
     *
     * Please be careful that moving thing isn't atomic operation. As a result, when user
     * drags a thing and moves it to a new position, this method will be called several times.
     * That's why we need {@link ThingManager#updateLocations(int, int)} to truly update its
     * location in database and keep stability.
     */
    public void move(int from, int to) {
        Thing temp = mThings.get(from);
        mThings.remove(from);
        mThings.add(to, temp);
    }

    /**
     * This method will only be called after all {@link ThingManager#move(int, int)}s
     * have been already called for better performance.
     */
    public void updateLocations(final int from, final int to) {
        int start = from < to ? from : to;
        int end = to > from ? to : from;
        final Long[] ids = new Long[end - start + 1];
        final Long[] locations = new Long[end - start + 1];

        for (int i = start; i <= end; i++) {
            ids[i - start] = mThings.get(i).getId();
            locations[i - start] = mThings.get(i).getLocation();
        }

        Arrays.sort(locations, Collections.reverseOrder());

        for (int i = start, j = 0; i <= end; i++, j++) {
            mThings.get(i).setLocation(locations[j]);
        }
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mDao.updateLocations(ids, locations);
            }
        });
    }

    /**
     * This method will be only called when a thing with {@param type} and {@param state}
     * has been "deleted", which can occur when creating and updating.
     *
     * Please be careful that we only handle creation for need-to-create NOTIFY_EMPTY under
     * {@link mLimit}. For other NOTIFY_EMPTYs, we should handle their creation in {@link mDao}.
     *
     * @return {@code true} if a NOTIFY_EMPTY is needed to create under current
     * limit({@link mLimit}) and we created it indeed. {@code false} otherwise.
     */
    public boolean createNEnow(@Thing.Type int type, @Thing.State int state, boolean addToThingsNow) {
        int[] limits = Thing.getLimits(type, state);
        for (int limit : limits) {
            if (mLimit == limit) {
                if (mThings.size() == 1) {
                    System.out.println("createNEnow: mHeaderId = " + mHeaderId);
                    Thing notifyEmpty = Thing.generateNotifyEmpty(limit, mHeaderId, mContext);
                    create(notifyEmpty, false, addToThingsNow);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean willCreateNEforOtherLimit(long id, @Thing.Type int type, @Thing.State int state,
                                              boolean updateState) {
        int[] limits = Thing.getLimits(type, state);
        for (int limit : limits) {
            if (mLimit != limit) {
                if (updateState ||
                        (!updateState && limit != Def.LimitForGettingThings.ALL_UNDERWAY)) {
                    Cursor cursor = mDao.getThingsCursorForDisplay(limit, null, 0);
                    long id1 = -1;
                    int count = 0;
                    while (cursor.moveToNext()) {
                        count++;
                        id1 = cursor.getLong(0);
                        if (count > 2) {
                            break;
                        }
                    }
                    cursor.close();
                    if (count == 2 && id == id1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * This method will be only called when a thing with {@param type} and {@param state}
     * has been "created", which can occur when creating and updating.
     *
     * Please be careful that we only handle deletion for possible-existed and need-to-delete
     * NOTIFY_EMPTY under {@link mLimit}. For other NOTIFY_EMPTYs, we should handle their
     * deletion in {@link mDao}.
     *
     * @return {@code true} if the first thing under current limit({@link mLimit}) is a NOTIFY_EMPTY
     *          and we have already deleted it by calling this. {@code false} otherwise.
     */
    public boolean deleteNEnow(@Thing.Type int type, @Thing.State int state) {
        int[] limits = Thing.getLimits(type, state);
        for (int limit : limits) {
            if (mLimit == limit) {
                Thing thing = mThings.get(1);
                int NEtype = thing.getType();
                if (NEtype >= Thing.NOTIFY_EMPTY_UNDERWAY) {
                    updateState(thing, 1, -1, Thing.UNDERWAY, Thing.DELETED_FOREVER, false, false);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return if {@link mThings} is "empty" for user-created things now.
     */
    public boolean isThingsEmpty() {
        return mThings.size() < 2 || mThings.get(1).getType() >= Thing.NOTIFY_EMPTY_UNDERWAY;
    }

    public Thing getSelectedThing() {
        for (Thing thing : mThings) {
            if (thing.isSelected()) return thing;
        }
        return null;
    }

    public Thing[] getSelectedThings() {
        List<Thing> selectedThings = new ArrayList<>();
        for (Thing thing : mThings) {
            if (thing.isSelected()) {
                selectedThings.add(thing);
            }
        }
        Thing[] ret = new Thing[selectedThings.size()];
        selectedThings.toArray(ret);
        return ret;
    }

    public void setSelectedTo(boolean selected) {
        int size = mThings.size();
        for (int i = 1; i < size; i++) {
            mThings.get(i).setSelected(selected);
        }
    }

    public int getSelectedCount() {
        int count = 0;
        for (Thing thing : mThings) {
            if (thing.isSelected()) count++;
        }
        return count;
    }

    private void updateHeader(int addSize) {
        mHeaderId += addSize;
        Thing header = mThings.get(0);
        if (header != null && header.getType() == Thing.HEADER) {
            header.setId(mHeaderId);
            header.setLocation(mHeaderId);
        }
    }
}
