package com.osint.unified.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.osint.unified.ui.theme.Intel

/**
 * What this app is, and what using it means, said once before it is used.
 *
 * The README told the developer to use the app only with appropriate
 * authorisation; the app never told anyone. There was no acknowledgement, no
 * record of why a case was opened, and no warning before a tool connected to
 * someone's infrastructure. Any professional user or employer would ask for
 * this before the app went near real work, and it is the user's own
 * protection if a case is ever questioned.
 */
@Composable
fun FirstRunScreen(onAccept: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Intel.Base) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(
                "OSINT UNIFIED",
                style = MaterialTheme.typography.titleLarge,
                color = Intel.TextHi
            )
            Text(
                "Before you start",
                style = MaterialTheme.typography.labelMedium,
                color = Intel.Cyan,
                modifier = Modifier.padding(top = 4.dp)
            )

            Para(
                "This app queries public sources about people, companies, " +
                    "domains and places. Everything it finds was already " +
                    "published somewhere. That does not make looking at it " +
                    "lawful in every case, or in every country."
            )
            Para(
                "Use it only where you are authorised to. Research, " +
                    "journalism, due diligence and security work on your own " +
                    "estate generally are; building a profile of a private " +
                    "individual who has not consented generally is not, and " +
                    "in many places is an offence."
            )

            Heading("Passive by default")
            Para(
                "Sources that merely ask a third party what it already knows " +
                    "run automatically. Sources that connect to the target — " +
                    "resolving its hosts, fetching its files, requesting " +
                    "profile URLs — do not, until you switch them on for that " +
                    "case. Those put your address in someone's logs."
            )

            Heading("Your data stays here")
            Para(
                "Cases are stored on this device only. Nothing is uploaded, " +
                    "there is no account, and the database is excluded from " +
                    "cloud backup. Anyone with your unlocked phone can read " +
                    "it, so lock your phone."
            )

            Heading("What it is not")
            Para(
                "Not a compliance tool and not evidence by itself. Sanctions " +
                    "searches run against a snapshot bundled at build time " +
                    "and are name matches, not determinations. Sources go " +
                    "down, change and get things wrong; the app marks what it " +
                    "could not reach, and you should check anything you rely " +
                    "on at its source."
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Intel.Cyan,
                    contentColor = Intel.Base
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("I UNDERSTAND", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Intel.Cyan,
        modifier = Modifier.padding(top = 18.dp)
    )
}

@Composable
private fun Para(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Intel.TextMid,
        modifier = Modifier.padding(top = 8.dp)
    )
}
