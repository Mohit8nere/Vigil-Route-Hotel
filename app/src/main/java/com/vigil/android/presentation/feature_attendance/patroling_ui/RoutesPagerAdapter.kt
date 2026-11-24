package com.vigil.android.presentation.feature_attendance.patroling_ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class RoutesPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> CurrentRouteFragment()
            1 -> PreviousRouteFragment()
            2 -> com.vigil.android.core.nav.VerificationTab()
            else -> com.vigil.android.features.surveys.SurveyTab()
        }
    }
}