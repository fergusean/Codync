import SwiftUI

/// The system text input (dictation, Scribble or keyboard, as the watch offers) as one mic button.
struct DictationButton: View {
    let submit: (String) -> Void

    var body: some View {
        TextFieldLink(prompt: Text("Message")) {
            Image(systemName: "mic.fill")
        } onSubmit: { text in
            let message = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !message.isEmpty { submit(message) }
        }
        .accessibilityLabel("Dictate a message")
    }
}
