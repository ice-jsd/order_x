import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from livepocket_lottery import (  # noqa: E402
    QuestionnaireValidationError,
    apply_configured_questionnaire_answers,
    build_questionnaire_preview_config,
    extract_questionnaire_questions,
    validate_questionnaire_config_or_raise,
)


CONFIRM_HTML = """
<table>
  <tr>
    <th>成人済みですか？ 必須</th>
    <td>
      <label><input type="radio" name="questionnaire_answers[adult]" value="成人済">成人済</label>
      <label><input type="radio" name="questionnaire_answers[adult]" value="未成年">未成年</label>
    </td>
  </tr>
  <tr>
    <th>同行希望日</th>
    <td><input type="text" name="questionnaire_answers[visit_day]" /></td>
  </tr>
  <tr>
    <th>備考</th>
    <td><textarea name="questionnaire_answers[note]"></textarea></td>
  </tr>
  <tr>
    <th>参加エリア 必須</th>
    <td>
      <select name="questionnaire_answers[area]" required>
        <option value="">選択してください</option>
        <option value="tokyo">東京</option>
        <option value="osaka">大阪</option>
      </select>
    </td>
  </tr>
  <tr>
    <th>希望特典</th>
    <td>
      <label><input type="checkbox" name="questionnaire_answers[bonus]" value="poster">ポスター</label>
      <label><input type="checkbox" name="questionnaire_answers[bonus]" value="badge">バッジ</label>
    </td>
  </tr>
</table>
"""


class LivePocketQuestionnaireTests(unittest.TestCase):
    def test_extract_questionnaire_questions_supports_multiple_field_types(self):
        questions = extract_questionnaire_questions(CONFIRM_HTML)
        self.assertEqual(
            [item["type"] for item in questions],
            ["radio", "select", "checkbox", "textarea", "text"],
        )
        self.assertEqual(questions[0]["label"], "成人済みですか？")
        self.assertEqual(questions[1]["options"][1]["label"], "東京")

    def test_apply_configured_questionnaire_answers_accepts_string_and_list_answers(self):
        questions = extract_questionnaire_questions(CONFIRM_HTML)
        config = build_questionnaire_preview_config(questions, preview_account_id=1, preview_session_id="session-1")
        config["answers"] = {
            "questionnaire_answers[adult]": "成人済",
            "questionnaire_answers[visit_day]": "2026-06-30",
            "questionnaire_answers[note]": "よろしくお願いします",
            "questionnaire_answers[area]": "tokyo",
            "questionnaire_answers[bonus]": ["poster", "badge"],
        }
        form_data = {}
        context = apply_configured_questionnaire_answers(form_data, questions, config)
        self.assertEqual(form_data["questionnaire_answers[adult]"], "成人済")
        self.assertEqual(form_data["questionnaire_answers[bonus]"], ["poster", "badge"])
        self.assertEqual(context["questionnaireAnswers"], 5)

    def test_validate_questionnaire_config_requires_signature_match(self):
        questions = extract_questionnaire_questions(CONFIRM_HTML)
        config = build_questionnaire_preview_config(questions, preview_account_id=1, preview_session_id="session-1")
        config["schemaSignature"] = "sha256:broken"
        with self.assertRaises(QuestionnaireValidationError):
            validate_questionnaire_config_or_raise(config, questions)

    def test_apply_configured_questionnaire_answers_reports_missing_answers(self):
        questions = extract_questionnaire_questions(CONFIRM_HTML)
        config = build_questionnaire_preview_config(questions, preview_account_id=1, preview_session_id="session-1")
        config["answers"] = {
            "questionnaire_answers[adult]": "成人済",
        }
        with self.assertRaises(QuestionnaireValidationError) as context:
            apply_configured_questionnaire_answers({}, questions, config)
        self.assertTrue(context.exception.missing_fields)


if __name__ == "__main__":
    unittest.main()
