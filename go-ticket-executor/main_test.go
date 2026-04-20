package main

import (
	"strings"
	"testing"

	"github.com/PuerkitoBio/goquery"
)

func TestDetectLivePocketTicketFieldNameFallsBackToEventCardID(t *testing.T) {
	html := `
<html><body>
  <input type="hidden" name="authenticity_token" value="token-1" />
  <button id="ev_2660700_tgl" type="button">toggle</button>
</body></html>`
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		t.Fatalf("parse html failed: %v", err)
	}
	field := detectLivePocketTicketFieldName(doc, html)
	if field != "tickets[2660700]" {
		t.Fatalf("unexpected ticket field: %s", field)
	}
}

func TestExtractLivePocketReserveInfoFromConfirmURL(t *testing.T) {
	eventID, reserveID := extractLivePocketReserveInfo("https://livepocket.jp/purchase/confirm?id=1034415&reserve_id=13830545", "")
	if eventID != "1034415" || reserveID != "13830545" {
		t.Fatalf("unexpected ids: event=%s reserve=%s", eventID, reserveID)
	}
}

func TestExtractLivePocketOrderNo(t *testing.T) {
	orderNo := extractLivePocketOrderNo(`<html><body><div>申込番号：LP-ORDER-123456</div></body></html>`)
	if orderNo != "LP-ORDER-123456" {
		t.Fatalf("unexpected order number: %s", orderNo)
	}
}
