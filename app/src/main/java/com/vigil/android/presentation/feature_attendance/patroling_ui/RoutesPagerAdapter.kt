package com.vigil.android.presentation.feature_attendance.patroling_ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class RoutesPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> CurrentRouteFragment()
            1 -> PreviousRouteFragment()
            else -> com.vigil.android.core.nav.VerificationTab()
        }
    }
}