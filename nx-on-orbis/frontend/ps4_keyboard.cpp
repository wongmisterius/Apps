// SPDX-License-Identifier: GPL-3.0-or-later
// eden-ps4: see ps4_keyboard.h.

#include <thread>

#include "common/logging.h"
#include "common/string_util.h"
#include "core/hle/service/am/frontend/applet_software_keyboard_types.h"
#include "ps4_keyboard.h"
#include "ps4_platform.h"

namespace Ps4 {

using Service::AM::Frontend::SwkbdReplyType;
using Service::AM::Frontend::SwkbdResult;
using Service::AM::Frontend::SwkbdTextCheckResult;
using Service::AM::Frontend::SwkbdType;

namespace {

/// The title shown on the console keyboard: the game's header, else its guide text.
std::u16string TitleOf(const Core::Frontend::KeyboardInitializeParameters& p) {
    if (!p.header_text.empty()) {
        return p.header_text;
    }
    if (!p.guide_text.empty()) {
        return p.guide_text;
    }
    return p.sub_text;
}

} // Anonymous namespace

SoftwareKeyboard::~SoftwareKeyboard() = default;

void SoftwareKeyboard::Close() const {}

void SoftwareKeyboard::InitializeKeyboard(
    bool is_inline, Core::Frontend::KeyboardInitializeParameters initialize_parameters,
    SubmitNormalCallback submit_normal_callback_, SubmitInlineCallback submit_inline_callback_) {
    std::lock_guard lock{mutex};
    if (is_inline) {
        submit_inline = std::move(submit_inline_callback_);
    } else {
        submit_normal = std::move(submit_normal_callback_);
    }
    parameters = std::move(initialize_parameters);
    inline_text.clear();
    LOG_INFO(Frontend, "PS4 keyboard: {} keyboard, header \"{}\", max {} characters",
             is_inline ? "inline" : "normal", Common::UTF16ToUTF8(parameters.header_text),
             parameters.max_text_length);
}

template <typename Done>
void SoftwareKeyboard::Prompt(std::u16string title, std::u16string initial, unsigned max_length,
                              Done done) const {
    bool numbers;
    {
        std::lock_guard lock{mutex};
        numbers = parameters.type == SwkbdType::NumberPad;
    }
    std::thread([title = std::move(title), initial = std::move(initial), max_length, numbers,
                 done = std::move(done)]() mutable {
        Ps4::RegisterThread("ime");
        std::u16string text;
        bool cancelled = false;
        if (!Ps4::ImeInput(title, initial, max_length, numbers, text, cancelled)) {
            // No console keyboard: keep the game moving with the text it suggested.
            text = initial.empty() ? std::u16string(u"Eden") : initial;
            cancelled = false;
        }
        done(cancelled, std::move(text));
    }).detach();
}

// Eden's callbacks are never called with `mutex` held: they can lead back into this applet.

void SoftwareKeyboard::FinishNormal(bool cancelled, std::u16string text) const {
    bool can_cancel;
    {
        std::lock_guard lock{mutex};
        last_text = text;
        can_cancel = !parameters.disable_cancel_button;
    }
    if (cancelled && can_cancel) {
        submit_normal(SwkbdResult::Cancel, std::move(text), true);
    } else {
        submit_normal(SwkbdResult::Ok, std::move(text), false);
    }
}

void SoftwareKeyboard::ShowNormalKeyboard() const {
    std::u16string title, initial;
    unsigned max;
    {
        std::lock_guard lock{mutex};
        title = TitleOf(parameters);
        initial = last_text.empty() ? parameters.initial_text : last_text;
        max = parameters.max_text_length;
    }
    Prompt(std::move(title), std::move(initial), max,
           [this](bool cancelled, std::u16string text) { FinishNormal(cancelled, std::move(text)); });
}

void SoftwareKeyboard::ShowTextCheckDialog(SwkbdTextCheckResult text_check_result,
                                           std::u16string text_check_message) const {
    LOG_INFO(Frontend, "PS4 keyboard: text check {} \"{}\"", static_cast<u32>(text_check_result),
             Common::UTF16ToUTF8(text_check_message));
    std::u16string last;
    unsigned max;
    {
        std::lock_guard lock{mutex};
        last = last_text;
        max = parameters.max_text_length;
    }
    if (text_check_result == SwkbdTextCheckResult::Confirm) {
        // The game asks "is this right?": accept what was typed.
        submit_normal(SwkbdResult::Ok, std::move(last), true);
        return;
    }
    // Failure: type it again, with the game's reason as the title.
    Prompt(std::move(text_check_message), std::move(last), max,
           [this](bool cancelled, std::u16string text) { FinishNormal(cancelled, std::move(text)); });
}

void SoftwareKeyboard::ShowInlineKeyboard(
    Core::Frontend::InlineAppearParameters appear_parameters) const {
    std::u16string title, initial;
    {
        std::lock_guard lock{mutex};
        title = TitleOf(parameters);
        initial = inline_text;
    }
    Prompt(std::move(title), std::move(initial), appear_parameters.max_text_length,
           [this](bool cancelled, std::u16string text) {
               if (cancelled) {
                   std::u16string current;
                   {
                       std::lock_guard lock{mutex};
                       current = inline_text;
                   }
                   const auto cursor = static_cast<s32>(current.size());
                   submit_inline(SwkbdReplyType::DecidedCancel, std::move(current), cursor);
                   return;
               }
               {
                   std::lock_guard lock{mutex};
                   inline_text = text;
               }
               const auto cursor = static_cast<s32>(text.size());
               submit_inline(SwkbdReplyType::ChangedString, text, cursor);
               submit_inline(SwkbdReplyType::DecidedEnter, std::move(text), cursor);
           });
}

void SoftwareKeyboard::HideInlineKeyboard() const {}

void SoftwareKeyboard::InlineTextChanged(Core::Frontend::InlineTextParameters text_parameters) const {
    {
        std::lock_guard lock{mutex};
        inline_text = text_parameters.input_text;
    }
    submit_inline(SwkbdReplyType::ChangedString, text_parameters.input_text,
                  text_parameters.cursor_position);
}

void SoftwareKeyboard::ExitKeyboard() const {}

} // namespace Ps4
