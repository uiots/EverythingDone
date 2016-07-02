package com.ywwynm.everythingdone.fragments;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;
import com.ywwynm.everythingdone.activities.DetailActivity;
import com.ywwynm.everythingdone.adapters.HabitRecordAdapter;
import com.ywwynm.everythingdone.model.Habit;
import com.ywwynm.everythingdone.utils.DateTimeUtil;
import com.ywwynm.everythingdone.utils.LocaleUtil;

/**
 * Created by ywwynm on 2016/3/3.
 * show habit detail in a DialogFragment
 */
public class HabitDetailDialogFragment extends BaseDialogFragment {

    public static final String TAG = "HabitDetailDialogFragment";
    private Habit mHabit;

    private TextView mTvCr;
    private TextView mTvTs;              // 坚持的周期数
    private TextView mTvTimes;
    private RecyclerView mRvRecord;

    public static HabitDetailDialogFragment newInstance() {
        Bundle args = new Bundle();
        HabitDetailDialogFragment fragment = new HabitDetailDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    public void setHabit(Habit habit) {
        mHabit = habit;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        TextView title = f(R.id.tv_habit_detail_title);
        DetailActivity activity = (DetailActivity) getActivity();
        int accentColor = activity.getAccentColor();
        title.setTextColor(accentColor);

        mTvCr     = f(R.id.tv_habit_detail_completion_rate);
        mTvTs     = f(R.id.tv_habit_detail_persist_in);
        mTvTimes  = f(R.id.tv_habit_detail_times);
        mRvRecord = f(R.id.rv_habit_detail_record);

        TextView getIt = f(R.id.tv_get_it_as_bt);
        getIt.setTextColor(accentColor);
        getIt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        initUI();

        return mContentView;
    }

    @Override
    protected int getLayoutResource() {
        return R.layout.fragment_habit_detail;
    }

    @SuppressLint("SetTextI18n")
    private void initUI() {
        mTvCr.setText(mHabit.getCompletionRate());

        int piT = mHabit.getPersistInT();
        Context context = App.getApp();
        mTvTs.setText((piT < 0 ? 0 : piT) + " " +
                    DateTimeUtil.getTimeTypeStr(mHabit.getType(), context));
        if (piT > 1 && LocaleUtil.isEnglish(context)) {
            mTvTs.append("s");
        }

        mTvTimes.setText("" + mHabit.getFinishedTimes());

        String record = mHabit.getRecord();
        int len = record.length();
        if (len < 30) {
            int add = 30 - len;
            for (int i = 0; i < add; i++) {
                record += "?";
            }
        } else {
            record = record.substring(len - 30, len);
        }

        Activity activity = getActivity();
        HabitRecordAdapter habitRecordAdapter = new HabitRecordAdapter(activity, record);
        mRvRecord.setAdapter(habitRecordAdapter);
        GridLayoutManager glm = new GridLayoutManager(activity, 6);
        mRvRecord.setLayoutManager(glm);
    }
}
