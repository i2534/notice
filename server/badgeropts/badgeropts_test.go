package badgeropts

import "testing"

func TestParamsNormalized(t *testing.T) {
	p := Params{}.Normalized()
	if p.MemTableMB != DefaultMemTableMB || p.BlockCacheMB != DefaultBlockCacheMB || p.NumMemtables != DefaultNumMemtables {
		t.Fatalf("empty Params: got %+v", p)
	}

	p2 := Params{MemTableMB: 8, BlockCacheMB: 16, NumMemtables: 3}.Normalized()
	if p2.MemTableMB != 8 || p2.BlockCacheMB != 16 || p2.NumMemtables != 3 {
		t.Fatalf("explicit Params: got %+v", p2)
	}

	p3 := Params{NumMemtables: 1}.Normalized()
	if p3.NumMemtables != 2 {
		t.Fatalf("NumMemtables=1 should clamp to 2, got %d", p3.NumMemtables)
	}
}
