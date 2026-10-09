// SPDX-License-Identifier: GPL-3.0-or-later
// eden-ps4: the Switch software keyboard applet on the PS4's own on-screen keyboard
// (sceImeDialog, see Ps4::ImeInput). Eden's default applet types "Eden" into every text field,
// which games that name things (Miis, islands, save files) cannot get past.

#pragma once

#include <mutex>
#include <string>

#include "core/frontend/applets/software_keyboard.h"

namespace Ps4 {

class SoftwareKeyboard final : public Core::Frontend::SoftwareKeyboardApplet {
public:
    ~SoftwareKeyboard() override;

    void Close() const override;
    void InitializeKeyboard(bool is_inline,
                            Core::Frontend::KeyboardInitializeParameters initialize_parameters,
                            SubmitNormalCallback submit_normal_callback_,
                            SubmitInlineCallback submit_inline_callback_) override;
    void ShowNormalKeyboard() const override;
    void ShowTextCheckDialog(Service::AM::Frontend::SwkbdTextCheckResult text_check_result,
                             std::u16string text_check_message) const override;
    void ShowInlineKeyboard(Core::Frontend::InlineAppearParameters appear_parameters) const override;
    void HideInlineKeyboard() const override;
    void InlineTextChanged(Core::Frontend::InlineTextParameters text_parameters) const override;
    void ExitKeyboard() const override;

private:
    /// Opens the console keyboard on its own thread (the applet calls arrive on emulator threads
    /// that must not block) and hands the result to `done(cancelled, text)`.
    template <typename Done>
    void Prompt(std::u16string title, std::u16string initial, unsigned max_length, Done done) const;

    void FinishNormal(bool cancelled, std::u16string text) const;

    mutable std::mutex mutex;
    Core::Frontend::KeyboardInitializeParameters parameters;
    mutable std::u16string inline_text;
    mutable std::u16string last_text;
    SubmitNormalCallback submit_normal;
    SubmitInlineCallback submit_inline;
};

} // namespace Ps4
