package com.vigil.android.presentation.feature_dashboard.officer_dashboard_screen.dummy_data

import com.vigil.android.core.di.model.dashboard_model.Officer
import com.vigil.android.utils.OfficerStatus

object DummyOfficerData {

    // TODO: replace with API call -> repository.getOfficers()
    val officers = listOf(
        Officer(
            "1",
            "1",
            "Officer Aman",
            "PAT-101",
            "BDG-001",
            "South Zone Route A",
            "21 Jul 2026",
            22.7196,
            75.8577,
            status = OfficerStatus.ON_DUTY
        ),
        Officer("2", "2", "Officer Rahul", "PAT-102", "BDG-002", "North Zone Route B", "22 Jul 2026", 22.7280, 75.8650, status = OfficerStatus.ON_BREAK),
        Officer("3", "3", "Officer Sneha", "PAT-103", "BDG-003", "East Zone Route C", "20 Jul 2026", 22.7100, 75.8700, status = OfficerStatus.OFF_DUTY),
        Officer("4", "4", "Officer Priya", "PAT-104", "BDG-004", "West Zone Route D", "23 Jul 2026", 22.7050, 75.8450, status = OfficerStatus.ON_DUTY),
        Officer("5", "5", "Officer Vikram", "PAT-105", "BDG-005", "Central Zone Route E", "19 Jul 2026", 22.7200, 75.8800, status = OfficerStatus.ON_DUTY)
    )
}